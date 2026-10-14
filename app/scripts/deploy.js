import { rmSync, existsSync, mkdirSync, readdirSync, readFileSync, writeFileSync, statSync } from "fs";
import { join } from "path";

// UpJasperReport6To7 是 Spring Boot JAR：靜態資源部署到 classpath 的 static/
// （Boot 直接服務 classpath:/static/，context path 為根 '/'）
const sourceDir = "dist";
const targetRoot = join("..", "src", "main", "resources", "static");
const targetAssets = join(targetRoot, "assets");
const targetIndex = join(targetRoot, "index.html");

// 📦 確保 static 目錄存在
if (!existsSync(targetRoot)) mkdirSync(targetRoot, { recursive: true });

// ✅ 複製新的 index.html
const sourceIndex = join(sourceDir, "index.html");
if (existsSync(sourceIndex)) {
    const indexContent = readFileSync(sourceIndex);
    writeFileSync(targetIndex, indexContent);
    console.log("✅ 已複製新的 index.html");
} else {
    console.error("❌ 源目錄中沒有找到 index.html");
}

// 🧹 先清空 target assets 目錄（若存在）再複製（舊 bundle hash 檔會被移除）
const sourceAssets = join(sourceDir, "assets");
if (existsSync(sourceAssets)) {
    if (existsSync(targetAssets)) {
        try {
            rmSync(targetAssets, { recursive: true, force: true });
            console.log("🧹 已清除舊的 assets 目錄");
        } catch (err) {
            console.error("❌ 清除舊的 assets 失敗:", err);
        }
    }

    function copyDirRecursive(src, dest) {
        if (!existsSync(dest)) {
            mkdirSync(dest, { recursive: true });
        }
        const files = readdirSync(src);
        for (const file of files) {
            const srcPath = join(src, file);
            const destPath = join(dest, file);
            const stat = statSync(srcPath);
            if (stat.isDirectory()) {
                copyDirRecursive(srcPath, destPath);
            } else {
                const data = readFileSync(srcPath);
                writeFileSync(destPath, data);
            }
        }
    }

    copyDirRecursive(sourceAssets, targetAssets);
    console.log("✅ 已複製 assets 目錄");
} else {
    console.error("❌ 源目錄中沒有找到 assets 目錄");
}

// 📄 複製 dist 根目錄的其他檔案（favicon.svg、icons.svg 等；vite 會把 public/ 拷到 dist 根）
for (const file of readdirSync(sourceDir)) {
    if (file === "index.html" || file === "assets") continue; // index.html 已複製、assets 已處理
    const srcPath = join(sourceDir, file);
    if (statSync(srcPath).isFile()) {
        writeFileSync(join(targetRoot, file), readFileSync(srcPath));
        console.log(`✅ 已複製 ${file}`);
    }
}

console.log("🎉 前端部署完成 → src/main/resources/static");
