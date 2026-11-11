#!/usr/bin/env bash
# ============================================================
# 批量构建「极客时间」课程阅读器
#
# 用法：
#   bash _阅读器模板/build_all.sh              # 构建清单里的全部课程
#   bash _阅读器模板/build_all.sh Redis        # 只跑名字里含 Redis 的课程
#
# 说明：
#   - 自动切到「极客时间」目录，不依赖从哪运行
#   - 单门失败不影响其它课程，失败详情写到 _阅读器模板/build_all.log
# ============================================================
set -o pipefail

cd "$(dirname "$0")/.." || exit 1
export PYTHONIOENCODING=utf-8
LOG="_阅读器模板/build_all.log"
: > "$LOG"

FILTER="${1:-}"
ok=0; fail=0
failed=""

run() {
  local name="$1"; shift
  if [ -n "$FILTER" ] && [ "${name#*"$FILTER"}" = "$name" ]; then
    return
  fi
  local out
  if out=$(python "_阅读器模板/build.py" "$name" "$@" 2>&1); then
    local info
    info=$(printf '%s' "$out" | grep -o '[0-9]* 组 / [0-9]* 讲' | tail -1)
    printf 'OK   %s  %s\n' "$name" "$info"
    printf '\n===== OK %s %s\n%s\n' "$name" "$info" "$out" >> "$LOG"
    ok=$((ok+1))
  else
    printf 'FAIL %s\n' "$name"
    printf '%s\n' "$out" | tail -3 | sed 's/^/     /'
    printf '\n===== FAIL %s\n%s\n' "$name" "$out" >> "$LOG"
    fail=$((fail+1))
    failed="$failed | $name"
  fi
}

run "10x 程序员工作法"          --author "郑晔"
run "Android 开发高手课"        --author "张绍文"
run "Go 语言核心 36 讲"          --author "郝林"
run "Go 语言第一课"              --author "Tony Bai"
run "Go 语言项目开发实战"        --author "孔令飞"
run "Java 性能调优实战"          --author "刘超"
run "Java 核心技术面试精讲"      --author "杨晓峰"
run "Kafka 核心技术与实战"       --author "胡夕"
run "Kafka 核心源码解读"         --author "胡夕"
run "MySQL实战45讲"              --author "林晓斌"
run "Python 核心技术与实战"      --author "景霄"
run "Redis 核心技术与实战"       --author "蒋德钧"
run "etcd 实战课"                --author "唐聪"
run "乔新亮的 CTO 成长复盘"      --author "乔新亮"
run "全链路压测实战 30 讲"       --author "高楼"
run "容器实战高手课"             --author "程远"
run "性能工程高手课"             --author "庄振运"
run "性能测试实战 30 讲"         --author "高楼"
run "技术管理实战 36 讲"         --author "刘建国"
run "持续交付 36 讲"             --author "王潇俊"
run "深入剖析 Kubernetes"        --author "张磊"
run "爱上跑步"                   --author "钱亮"
run "玩转 Vue 3 全家桶"          --author "大圣"
run "程序员的数学基础课"         --author "黄申"
run "算法实战高手课"             --author "微扰君"
run "许式伟的架构课"             --author "许式伟"
run "设计模式之美"               --author "王争" --role "Google 工程师"
run "说透敏捷"                   --author "宋宁"
run "趣谈 Linux 操作系统"        --author "刘超"
run "趣谈网络协议"               --author "刘超"
run "软件测试 52 讲"             --author "茹炳晟"
run "重学前端"                   --author "程劭非"
run "高楼的性能工程实战课"       --author "高楼"

echo "==== 成功 $ok / 失败 $fail ===="
if [ "$fail" -gt 0 ]; then
  echo "失败课程:$failed"
  echo "详情见 $LOG"
fi
