#!/usr/bin/env node
/**
 * 打安卓 APK：node android/tools/build-apk.js [--release]
 *
 *   1) 找一个能用的 JDK(>=17) 和 Gradle(>=8.7)，找不到就说清楚缺什么
 *   2) 在 android/ 里跑 assembleDebug / assembleRelease
 *   3) 把产物拷到 out/GeekTimeReader-<versionName>-<variant>.apk
 *
 * 环境变量（都可选）：JAVA_HOME / GRADLE_HOME / GRADLE_BIN / ANDROID_HOME
 */
const fs = require("fs");
const path = require("path");
const os = require("os");
const { spawnSync } = require("child_process");

const ROOT = path.resolve(__dirname, "..", "..");
const ANDROID_DIR = path.join(ROOT, "android");
const WIN = process.platform === "win32";
const EXE = WIN ? ".exe" : "";

const release = process.argv.includes("--release");
const task = release ? "assembleRelease" : "assembleDebug";
const variant = release ? "release" : "debug";

function isFile(p) {
  try {
    return fs.statSync(p).isFile();
  } catch {
    return false;
  }
}

function isDir(p) {
  try {
    return fs.statSync(p).isDirectory();
  } catch {
    return false;
  }
}

function readVersion() {
  const file = path.join(ANDROID_DIR, "version.properties");
  const text = fs.readFileSync(file, "utf8");
  const name = (text.match(/^versionName=(.+)$/m) || [])[1];
  if (!name) throw new Error("android/version.properties 缺少 versionName");
  return name.trim();
}

function javaMajor(dir) {
  const bin = path.join(dir, "bin", "java" + EXE);
  if (!isFile(bin)) return 0;
  const r = spawnSync(bin, ["-version"], { encoding: "utf8" });
  const out = (r.stderr || "") + (r.stdout || "");
  const m = out.match(/version "(\d+)(?:\.(\d+))?/);
  if (!m) return 0;
  let major = Number(m[1]);
  if (major === 1) major = Number(m[2] || 0);
  return major;
}

function findJdk() {
  const candidates = [];
  if (process.env.JAVA_HOME) candidates.push(process.env.JAVA_HOME);
  const roots = [
    process.env.ProgramFiles,
    process.env.ProgramFiles ? path.join(process.env.ProgramFiles, "Java") : null,
    process.env.ProgramFiles ? path.join(process.env.ProgramFiles, "Eclipse Adoptium") : null,
    "/Library/Java/JavaVirtualMachines",
    "/usr/lib/jvm",
    path.join(os.homedir(), ".jdks"),
    "D:/develop/Java",
    "C:/develop/Java",
    "D:/Java",
    "C:/Java",
  ].filter(Boolean);
  for (const root of roots) {
    let entries = [];
    try {
      entries = fs.readdirSync(root);
    } catch {
      continue;
    }
    for (const name of entries) {
      const p = path.join(root, name);
      if (!isDir(p)) continue;
      if (isDir(path.join(p, "Contents", "Home", "bin"))) {
        candidates.push(path.join(p, "Contents", "Home"));
      } else if (isDir(path.join(p, "bin"))) {
        candidates.push(p);
      }
    }
  }
  let best = null;
  for (const dir of candidates) {
    const major = javaMajor(dir);
    if (major >= 17 && (!best || major > best.major)) best = { dir, major };
  }
  return best;
}

function gradleCandidates() {
  const list = [];
  if (process.env.GRADLE_BIN) list.push(process.env.GRADLE_BIN);
  if (process.env.GRADLE_HOME) {
    list.push(path.join(process.env.GRADLE_HOME, "bin", "gradle" + (WIN ? ".bat" : "")));
  }
  list.push(path.join(ANDROID_DIR, WIN ? "gradlew.bat" : "gradlew"));
  for (const dir of (process.env.PATH || "").split(path.delimiter)) {
    if (!dir) continue;
    list.push(path.join(dir, "gradle" + (WIN ? ".bat" : "")));
    list.push(path.join(dir, "gradle"));
  }
  const roots = [
    path.join(ANDROID_DIR, ".tools"),
    "D:/develop",
    "C:/develop",
    path.join(os.homedir(), ".gradle", "wrapper", "dists"),
  ];
  for (const root of roots) {
    let entries = [];
    try {
      entries = fs.readdirSync(root);
    } catch {
      continue;
    }
    for (const name of entries) {
      const p = path.join(root, name);
      if (!isDir(p)) continue;
      if (root.endsWith(path.join("wrapper", "dists"))) {
        if (!name.startsWith("gradle-")) continue;
        for (const hash of fs.readdirSync(p)) {
          const hashDir = path.join(p, hash);
          if (!isDir(hashDir)) continue;
          for (const g of fs.readdirSync(hashDir)) {
            if (g.startsWith("gradle-")) {
              list.push(path.join(hashDir, g, "bin", "gradle" + (WIN ? ".bat" : "")));
            }
          }
        }
      } else if (/^gradle[-.]?\d/i.test(name)) {
        list.push(path.join(p, "bin", "gradle" + (WIN ? ".bat" : "")));
      }
    }
  }
  return list;
}

function findGradle() {
  for (const p of gradleCandidates()) {
    if (isFile(p)) return p;
  }
  return null;
}

function findSdk() {
  const fromEnv = process.env.ANDROID_HOME || process.env.ANDROID_SDK_ROOT;
  if (fromEnv && isDir(fromEnv)) return fromEnv;
  try {
    const txt = fs.readFileSync(path.join(ANDROID_DIR, "local.properties"), "utf8");
    const m = txt.match(/^sdk\.dir=(.+)$/m);
    if (m) {
      const p = m[1].trim().replace(/\\/g, "/");
      if (isDir(p)) return p;
    }
  } catch {}
  return null;
}

function fail(msg) {
  console.error("\n  ✗ " + msg + "\n");
  process.exit(1);
}

function main() {
  const version = readVersion();
  const jdk = findJdk();
  if (!jdk) fail("没找到 JDK 17 或更高版本。装一个（如 Temurin 21）后重试，或设 JAVA_HOME 指过去。");
  const gradle = findGradle();
  if (!gradle) {
    fail(
      "没找到 Gradle。三种办法任选其一：\n" +
        "    1) 下载 gradle-8.9-bin.zip 解压到 D:/develop/ 或 android/.tools/\n" +
        "    2) 设置 GRADLE_HOME 指向解压目录\n" +
        "    3) 直接用 gradle.bat assembleDebug 手动构建"
    );
  }
  const sdk = findSdk();
  console.log("  JDK    " + jdk.dir + "  (" + jdk.major + ")");
  console.log("  Gradle " + gradle);
  console.log("  SDK    " + (sdk || "未检测到，交给 Gradle 自己找（android/local.properties）"));
  console.log("  构建   " + task + "  v" + version + "\n");

  const env = {
    ...process.env,
    JAVA_HOME: jdk.dir,
    PATH: path.join(jdk.dir, "bin") + path.delimiter + (process.env.PATH || ""),
  };
  if (sdk) {
    env.ANDROID_HOME = sdk;
    env.ANDROID_SDK_ROOT = sdk;
  }

  const r = spawnSync(gradle, [task, "--no-daemon", "-p", ANDROID_DIR], {
    stdio: "inherit",
    env,
    shell: WIN && /\.bat$/i.test(gradle),
  });
  if (r.status !== 0) fail("Gradle 构建失败，日志见上。");

  const apk = path.join(ANDROID_DIR, "app", "build", "outputs", "apk", variant, "app-" + variant + ".apk");
  if (!isFile(apk)) fail("构建结束但没找到产物：" + apk);

  const outDir = path.join(ROOT, "out");
  fs.mkdirSync(outDir, { recursive: true });
  const target = path.join(outDir, "GeekTimeReader-" + version + "-" + variant + ".apk");
  const part = target + ".part";
  try {
    fs.unlinkSync(part);
  } catch {}
  fs.copyFileSync(apk, part);
  try {
    fs.unlinkSync(target);
  } catch {}
  fs.renameSync(part, target);

  const size = (fs.statSync(target).size / 1024 / 1024).toFixed(1);
  console.log("\n  ✓ " + path.relative(ROOT, target).replace(/\\/g, "/") + "  (" + size + " MB)\n");
}

main();
