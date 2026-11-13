#!/bin/bash
# macOS 版启动脚本（功能对齐 Windows 的 serve.bat）
#   双击运行：本地 8888 起静态服务（带口令门禁）+ SSH 反向隧道到公网
#   用完回到本窗口按 Ctrl-C 即可停掉「本脚本启动的」后台进程
#
# 与 serve.bat 一致的关键行为：
#   1) auth.json 缺失时提示设置口令并退出
#   2) 127.0.0.1:8888 已在监听就复用，绝不重复启动，也不会杀掉已有进程
#   3) 公网 8878 已可达就不动健康的隧道（否则 fuser -k 会杀掉正在用的会话）
#   4) 建隧道前清理远端僵死监听，建立后用 ss -tln 复核绑定，连续 3 次失败即报错
#   5) 所有 ssh 走 BatchMode / ConnectTimeout，不会卡在主机指纹确认
#
# 额外增强（macOS 版原有）：隧道断线自动重连，指数退避 + 稳定计数：
#   断开后等 3s×连续失败次数（最长 30s）再试；连满 20s 没掉就重新计数；
#   连续两次「刚连上就断」会先清掉服务器上残留的监听再重连。
#   默认连续失败 10 次后放弃，可用环境变量覆盖（0 = 不限次数）：
#     MAX_RETRIES=0 ./serve.command
#     RETRY_BASE=5 RETRY_MAX_WAIT=60 ./serve.command
#
# 课程目录可外置：直接改下方 COURSE_DIR 的默认值，或用 GK_DATA_DIR 环境变量指定。
# 数据目录里只放各门课程的子目录；封面 / 清单 / 模板 / 口令仍在脚本所在目录。
#
# 首次使用（Gatekeeper / 执行权限）：
#   chmod +x serve.command
#   xattr -d com.apple.quarantine serve.command
set -u

DIR="$(cd "$(dirname "$0")" && pwd)"
cd "$DIR" || exit 1

PORT=8888
REMOTE_USER=root
REMOTE_HOST=139.198.29.121
REMOTE_PORT=8878
REMOTE="$REMOTE_USER@$REMOTE_HOST"
PYTHON="${PYTHON:-python3}"
SSHOPT=(-o BatchMode=yes -o ConnectTimeout=8)

# 课程数据目录（只放各门课程的一级子目录）。留空 = 与脚本同目录。
# 直接改下一行的默认值，或运行时：GK_DATA_DIR="/Volumes/USB/课程" ./serve.command
COURSE_DIR="/Volumes/Macintosh HD/Users/极客时间"
if [ -n "$COURSE_DIR" ] && [ ! -d "$COURSE_DIR" ]; then
  echo "  [ERROR] course data folder not found: $COURSE_DIR"
  read -r -p "Press Enter to close..."
  exit 1
fi

# 重连参数（环境变量可覆盖）
MAX_RETRIES="${MAX_RETRIES:-10}"       # 连续失败上限，0 = 不限
RETRY_BASE="${RETRY_BASE:-3}"          # 退避基数（秒）
RETRY_MAX_WAIT="${RETRY_MAX_WAIT:-30}"
STABLE_SECS=20                          # 连续在线超过这个秒数就算稳定
MAX_BIND_TRIES=3                        # 远端绑定连续失败上限（对齐 serve.bat）

case "$MAX_RETRIES" in ''|*[!0-9]*) MAX_RETRIES=10 ;; esac
case "$RETRY_BASE" in ''|*[!0-9]*) RETRY_BASE=3 ;; esac
case "$RETRY_MAX_WAIT" in ''|*[!0-9]*) RETRY_MAX_WAIT=30 ;; esac

HTTP_PID=""
SSH_PID=""
HTTP_LOG=""
STOPPING=0
FAILS=0
QUICK_FAILS=0
BIND_FAILS=0
HINTED=0

echo
echo "  ==== geekbang offline reader (macOS) ===="
echo

cleanup() {
  local rc=$?
  trap - EXIT INT TERM
  STOPPING=1
  [ -n "$SSH_PID" ] && kill "$SSH_PID" 2>/dev/null
  [ -n "$HTTP_PID" ] && kill "$HTTP_PID" 2>/dev/null
  wait 2>/dev/null
  echo
  echo "Stopped."
  exit "$rc"
}
trap cleanup EXIT INT TERM

# 0) 口令文件与依赖命令
if [ ! -f "$DIR/auth.json" ]; then
  echo "  [auth] auth.json not found. Set the access password first:"
  echo "    $PYTHON \"$DIR/serve.py\" --set-password"
  echo
  read -r -p "Press Enter to close..."
  exit 1
fi

for cmd in "$PYTHON" ssh curl lsof; do
  if ! command -v "$cmd" >/dev/null 2>&1; then
    echo "  [ERROR] $cmd not found (set PYTHON=/path/to/python3 if needed)"
    read -r -p "Press Enter to close..."
    exit 1
  fi
done

# 1) 本地静态服务：复用已有监听，不重复启动（serve.bat 的关键保护）
listener_pids() { lsof -nP -ti "tcp:$PORT" -sTCP:LISTEN 2>/dev/null; }

if [ -n "$(listener_pids)" ]; then
  echo "  [http] 127.0.0.1:$PORT already listening - reused"
else
  LOGDIR="${TMPDIR:-/tmp}"
  HTTP_LOG="$(mktemp "$LOGDIR/gk-http.XXXXXX" 2>/dev/null || echo "$DIR/.gk-http.log")"
  if [ -n "$COURSE_DIR" ]; then
    "$PYTHON" "$DIR/serve.py" "$PORT" "$DIR" --bind 127.0.0.1 \
        --data-dir "$COURSE_DIR" >"$HTTP_LOG" 2>&1 &
  else
    "$PYTHON" "$DIR/serve.py" "$PORT" "$DIR" --bind 127.0.0.1 >"$HTTP_LOG" 2>&1 &
  fi
  HTTP_PID=$!
  echo "  [http] starting local server ..."
  for _ in 1 2 3 4 5; do
    [ -n "$(listener_pids)" ] && break
    kill -0 "$HTTP_PID" 2>/dev/null || break
    sleep 1
  done
  if [ -z "$(listener_pids)" ]; then
    echo "  [ERROR] local server did not start. Log: $HTTP_LOG"
    tail -20 "$HTTP_LOG" 2>/dev/null | sed 's/^/    /'
    read -r -p "Press Enter to close..."
    exit 1
  fi
fi

# 2) 公网已可达就不碰健康的隧道（serve.bat 的 curl 探测）
if curl -s -o /dev/null -m 5 "http://$REMOTE_HOST:$REMOTE_PORT/" 2>/dev/null; then
  echo
  echo "  [ssh ] public $REMOTE_HOST:$REMOTE_PORT already reachable - tunnel untouched"
  echo
  echo "  OK   http://$REMOTE_HOST:$REMOTE_PORT/"
  echo "  No tunnel/HTTP process was started by this script."
  read -r -p "Press Enter to close..."
  exit 0
fi

# 3) 清掉远端僵死的旧监听
#    隧道客户端异常断开时远端 sshd 会残留监听，新隧道绑定失败只打 warning 就静默继续。
clean_remote() {
  ssh "${SSHOPT[@]}" "$REMOTE" \
    "command -v fuser >/dev/null 2>&1 && fuser -k $REMOTE_PORT/tcp 2>/dev/null; \
     command -v lsof  >/dev/null 2>&1 && lsof -ti tcp:$REMOTE_PORT | xargs kill -9 2>/dev/null; \
     echo cleaned" 2>&1 | sed 's/^/  ssh: /'
}

remote_listening() {
  ssh "${SSHOPT[@]}" "$REMOTE" \
    "ss -tln 2>/dev/null | grep -q ':$REMOTE_PORT'" >/dev/null 2>&1
}

retry_label() {
  if [ "$MAX_RETRIES" -gt 0 ]; then
    echo "第 $1/$MAX_RETRIES 次"
  else
    echo "第 $1 次（不限）"
  fi
}

print_hints() {
  echo "  排查："
  echo "  1) 公钥是否已装到服务器：把 ~/.ssh/id_ed25519.pub（或 id_rsa.pub）追加到 $REMOTE_USER 的 authorized_keys"
  echo "  2) 已启用 BatchMode，不会卡在主机指纹确认；首次连接请先 ssh $REMOTE 一次"
  echo "  3) 服务器 $REMOTE_PORT 是否被别的进程占用 / 云安全组是否放行 / GatewayPorts yes"
}

echo "  [ssh ] cleaning stale listener on remote:$REMOTE_PORT ..."
clean_remote || echo "  [warn] 清理失败（可能连不上服务器）"
sleep 1

# 4) SSH 反向隧道：远端 8878 -> 本地 8888；绑定失败快速重试，断开自动重连
#    ExitOnForwardFailure: 绑定失败立即退出（不再静默）
#    ServerAlive*: 本机断网/休眠后及时断开，避免远端留下僵死会话
connect() {
  local started lived code wait_s up http_alive

  while [ "$STOPPING" -eq 0 ]; do
    if [ "$FAILS" -gt 0 ]; then
      echo "[tunnel] 重连 $(retry_label "$FAILS") ..."
    fi

    started=$(date +%s)
    ssh -N "${SSHOPT[@]}" \
        -o ExitOnForwardFailure=yes -o ServerAliveInterval=30 -o ServerAliveCountMax=3 \
        -R "$REMOTE_PORT:127.0.0.1:$PORT" "$REMOTE" &
    SSH_PID=$!

    # 隧道是否真的建起来了：进程存活 + 远端确实在监听
    sleep 3
    up=1
    if ! kill -0 "$SSH_PID" 2>/dev/null; then
      echo "  [warn] tunnel NOT established"
      up=0
    elif ! remote_listening; then
      echo "  [warn] remote:$REMOTE_PORT has no listener (bind failed)"
      up=0
      kill "$SSH_PID" 2>/dev/null
    else
      echo "  [ok] tunnel up -> http://$REMOTE_HOST:$REMOTE_PORT/"
    fi

    wait "$SSH_PID"
    code=$?
    SSH_PID=""
    [ "$STOPPING" -eq 1 ] && return

    # 本地服务没了就没必要继续重连（复用场景看端口监听）
    http_alive=1
    if [ -n "$HTTP_PID" ]; then
      kill -0 "$HTTP_PID" 2>/dev/null || http_alive=0
    else
      [ -n "$(listener_pids)" ] || http_alive=0
    fi
    if [ "$http_alive" -eq 0 ]; then
      echo "[tunnel] 本地静态服务已退出，停止重连"
      STOPPING=1
      return
    fi

    # 绑定失败：清理远端残留后立即重试，连续 3 次失败就明确报错
    if [ "$up" -eq 0 ]; then
      BIND_FAILS=$((BIND_FAILS + 1))
      if [ "$BIND_FAILS" -ge "$MAX_BIND_TRIES" ]; then
        echo
        echo "  [ERROR] remote:$REMOTE_PORT still has no listener after $MAX_BIND_TRIES attempts."
        echo "          Check ssh keys and remote GatewayPorts yes."
        STOPPING=1
        return
      fi
      echo "  [ssh ] bind failed, retry $BIND_FAILS/$MAX_BIND_TRIES ..."
      clean_remote || echo "  [warn] 清理失败（可能连不上服务器），继续重试"
      sleep 3
      continue
    fi

    BIND_FAILS=0
    if [ "$code" -eq 0 ]; then
      echo "[tunnel] ssh 正常退出"
      STOPPING=1
      return
    fi

    lived=$(( $(date +%s) - started ))
    [ "$lived" -ge "$STABLE_SECS" ] && FAILS=0
    FAILS=$((FAILS + 1))
    if [ "$lived" -lt 10 ]; then
      QUICK_FAILS=$((QUICK_FAILS + 1))
    else
      QUICK_FAILS=0
    fi

    echo
    echo "[tunnel] 连接断开（ssh 退出码 ${code}，存活 ${lived} 秒）"
    if [ "$HINTED" -eq 0 ]; then
      print_hints
      HINTED=1
    fi

    if [ "$MAX_RETRIES" -gt 0 ] && [ "$FAILS" -gt "$MAX_RETRIES" ]; then
      echo
      echo "[tunnel] 已连续失败 $MAX_RETRIES 次，停止重连。"
      echo "        检查网络和公钥后重新双击本脚本（MAX_RETRIES 可调，0 = 不限）。"
      STOPPING=1
      return
    fi

    if [ "$QUICK_FAILS" -ge 2 ]; then
      echo "[tunnel] 连续快速断开，清理服务器上残留的 $REMOTE_PORT 监听…"
      clean_remote || echo "[tunnel] 清理失败（可能连不上服务器），继续重试"
      QUICK_FAILS=0
    fi

    wait_s=$((RETRY_BASE * FAILS))
    [ "$wait_s" -gt "$RETRY_MAX_WAIT" ] && wait_s=$RETRY_MAX_WAIT
    echo "[tunnel] ${wait_s} 秒后自动重连（$(retry_label "$FAILS")，Ctrl-C 退出）"
    sleep "$wait_s"
  done
}

echo
echo "  http://$REMOTE_HOST:$REMOTE_PORT/   (tunnel)"
echo "  http://127.0.0.1:$PORT/            (local)"
echo
if [ -n "$HTTP_LOG" ]; then
  echo "  http log: $HTTP_LOG"
fi
echo "  断线重连: $( [ "$MAX_RETRIES" -gt 0 ] && echo "最多 $MAX_RETRIES 次" || echo "不限次数")"
echo "  Ctrl-C to stop."
echo

connect
