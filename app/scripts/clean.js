import { rmSync, existsSync } from "fs";

// 清空 dist（免額外套件，直接掃 dist 目錄，不用 rimraf）
if (existsSync("dist")) {
  rmSync("dist", { recursive: true, force: true });
  console.log("🧹 已清除 dist");
}
