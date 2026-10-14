import { join } from "path";
import { writeFileSync, mkdirSync, readFileSync } from "fs";
import { fileURLToPath } from "url";

// 獲取當前模組的目錄（解決 ES Module 的 __dirname 問題）
const __filename = fileURLToPath(import.meta.url);
const __dirname = join(__filename, "..");

const version = `v${new Date().toISOString().replace(/[-:T.Z]/g, "").slice(0, 12)}`;
const buildTime = new Date().toISOString();

// 發布 jar 版號：讀取 repo 根目錄 pom.xml 本專案的 <version>
// （版號規範 MAJOR.MINOR.PATCH+YYYYMMDD）
let jarVersion = "";
try {
  const pom = readFileSync(join(__dirname, "../../pom.xml"), "utf-8");
  const m = pom.match(/<artifactId>upjasperreport6to7<\/artifactId>\s*<version>([^<]+)<\/version>/);
  jarVersion = m ? m[1] : "";
} catch {
  /* pom 讀不到時不影響前端 build */
}

// 計算目標路徑（基於腳本所在目錄）：app/scripts → app/public/assets
const publicPath = join(__dirname, "../public/assets");
mkdirSync(publicPath, { recursive: true });

const filePath = join(publicPath, "version.json");
writeFileSync(filePath, JSON.stringify({ version, buildTime, jarVersion }, null, 2), "utf-8");

console.log(`✅ 已產生 version.json -> ${version} (${buildTime}), jar 版號 ${jarVersion || "(未讀到)"}`);
