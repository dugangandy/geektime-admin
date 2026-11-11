#!/usr/bin/env node
/**
 * 版本号自增：npm run bump
 *
 * 改 android/version.properties（Gradle 与打包脚本的唯一版本源），
 * 并同步根 package.json 的 version，避免两处漂移。
 *
 * 例：1.0.0 / 10000 -> 1.0.1 / 10001
 * SKIP_BUMP=1 npm run bump 跳过。
 */
const fs = require("fs");
const path = require("path");

const ROOT = path.resolve(__dirname, "..", "..");
const PROPS = path.join(ROOT, "android", "version.properties");
const PKG = path.join(ROOT, "package.json");

if (process.env.SKIP_BUMP === "1") {
  console.log("[version] SKIP_BUMP=1，跳过版本号自增");
  process.exit(0);
}

const text = fs.readFileSync(PROPS, "utf8");
const pick = (key) => {
  const m = new RegExp("^" + key + "=(.*)$", "m").exec(text);
  return m ? m[1].trim() : "";
};

const name = pick("versionName");
const m = /^(\d+)\.(\d+)\.(\d+)$/.exec(name);
if (!m) {
  console.error("[version] android/version.properties 的 versionName 不是 x.y.z：" + name);
  process.exit(1);
}

const major = Number(m[1]);
const minor = Number(m[2]);
const patch = Number(m[3]);
const next = major + "." + minor + "." + (patch + 1);
// versionCode 规则：major*10000 + minor*100 + patch，与当前 1.0.0 -> 10000 一致
const nextCode = major * 10000 + minor * 100 + (patch + 1);

let out = text
  .replace(/^versionName=.*$/m, "versionName=" + next)
  .replace(/^versionCode=.*$/m, "versionCode=" + nextCode);
if (!/^versionCode=/m.test(out)) out = out.replace(/\s*$/, "") + "\nversionCode=" + nextCode + "\n";
fs.writeFileSync(PROPS, out);

if (fs.existsSync(PKG)) {
  try {
    const pkg = JSON.parse(fs.readFileSync(PKG, "utf8"));
    pkg.version = next;
    fs.writeFileSync(PKG, JSON.stringify(pkg, null, 2) + "\n");
  } catch (e) {
    console.warn("[version] package.json 更新失败（不影响打包）：" + e.message);
  }
}

console.log("[version] " + name + " (" + pick("versionCode") + ") -> " + next + " (" + nextCode + ")");
