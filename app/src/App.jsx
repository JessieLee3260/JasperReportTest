import { useCallback, useEffect, useMemo, useState } from "react";
import { api } from "./api";
import { useBatchJob } from "./hooks/useBatchJob";
import DirBrowser from "./components/DirBrowser";
import "./App.css";

const EMPTY_FORM = {
  sourceDir: "",
  outDir: "",
  reportFile: "",
  dryRun: true,
  inPlace: false,
};

/** 目錄視窗標題（依欄位） */
const BROWSE_TITLES = {
  sourceDir: "選「報表來源資料夾」（.jrxml 所在）",
  outDir: "選「輸出資料夾」（.jasper 存放位置）",
  reportFile: "選「JSON 報告存放資料夾」",
};

/** 狀態 → 白話標籤 */
const STATE_LABEL = {
  IDLE: "待命",
  RUNNING: "處理中",
  DONE: "已完成",
  FAILED: "中斷",
  CANCELLED: "已取消",
};

function buildPayload(form) {
  const p = { inPlace: form.inPlace, dryRun: form.dryRun };
  for (const k of ["sourceDir", "outDir", "reportFile"]) {
    const v = form[k].trim();
    if (v) p[k] = v;
  }
  return p;
}

function downloadJson(data, filename) {
  const blob = new Blob([JSON.stringify(data, null, 2)], { type: "application/json" });
  const url = URL.createObjectURL(blob);
  const a = document.createElement("a");
  a.href = url;
  a.download = filename;
  a.click();
  URL.revokeObjectURL(url);
}

/* ───────────── 批次參數 ───────────── */
function ParamsCard({ form, setForm, busy, scanning, onScan, onRun, onBrowse, canDownload, onDownload }) {
  const bind = (key) => ({
    value: form[key],
    onChange: (e) => setForm((f) => ({ ...f, [key]: e.target.value })),
  });
  // 互斥勾選：勾選「只驗證」會自動取消「就地覆寫」，反之亦然（不能同時勾選）
  const checkExclusive = (key, otherKey) => ({
    checked: form[key],
    onChange: (e) =>
      setForm((f) => {
        const next = { ...f, [key]: e.target.checked };
        if (e.target.checked) next[otherKey] = false;
        return next;
      }),
  });
  const browseBtn = (key) => (
      <button type="button" className="btn btn-browse" onClick={() => onBrowse(key)} title="開啟目錄視窗選資料夾">
        📂 瀏覽…
      </button>
  );
  const effInPlace = form.inPlace || !form.outDir.trim();
  const showWarn = effInPlace && !form.dryRun;

  return (
      <section className="card">
        <h2>批次參數設定</h2>
        <div className="grid">
          <div className="form-field form-field-source">
            <label htmlFor="sourceDir">報表來源資料夾（.jrxml，會自動遞迴掃描）</label>
            <div className="input-row">
              <input type="text" id="sourceDir" {...bind("sourceDir")} placeholder="留空 = 沿用系統預設；或點右側按鈕選資料夾" />
              {browseBtn("sourceDir")}
            </div>
          </div>
          <div className="form-field">
            <label htmlFor="outDir">輸出資料夾（.jasper 存放位置；驗證時免填；空值 = 寫入來源資料夾）</label>
            <div className="input-row">
              <input type="text" id="outDir" {...bind("outDir")} placeholder="空值 = 寫入來源資料夾（與 .jrxml 同位置）" />
              {browseBtn("outDir")}
            </div>
          </div>
          <div className="form-field">
            <label htmlFor="reportFile">JSON 報告存放路徑</label>
            <div className="input-row">
              <input type="text" id="reportFile" {...bind("reportFile")} placeholder="留空 = 系統預設路徑；選資料夾會存成其下 recompile-report.json" />
              {browseBtn("reportFile")}
            </div>
            <div className="field-note">用「瀏覽」選資料夾時，報告會存成該資料夾下的 recompile-report.json。</div>
          </div>
        </div>

        <div className="checks">
          <label>
            <input type="checkbox" {...checkExclusive("dryRun", "inPlace")} /> 只驗證、不寫入檔案（建議初次執行先勾選）
          </label>
          <label>
            <input type="checkbox" {...checkExclusive("inPlace", "dryRun")} /> 就地覆寫：直接取代來源資料夾內的舊 .jasper
          </label>
          <small className="checks-note">兩個勾選互斥：勾一個會自動取消另一個</small>
        </div>

        {showWarn && (
            <div className="warn">
              ⚠ 目前為正式寫入模式：.jasper 將寫入來源資料夾（「就地覆寫」已勾選，或輸出資料夾為空），會直接替換掉舊版 .jasper 檔案。建議先勾選「只驗證」確認無誤後再執行。
            </div>
        )}

        <div className="toolbar">
          <button className="btn btn-primary" onClick={onRun} disabled={busy}>
            {busy ? "處理中…" : "🚀 開始執行編譯"}
          </button>
          <button className="btn btn-ghost" onClick={onScan} disabled={scanning || busy}>
            {scanning ? "掃描中…" : "🔍 先預覽檔案清單"}
          </button>
          {canDownload && (
              <button className="btn btn-ghost" onClick={onDownload}>
                📥 下載分析報告 (JSON)
              </button>
          )}
        </div>
        <div className="hint">
          提示：未填寫的欄位將自動沿用系統預設值。若「只驗證」與「就地覆寫」皆未勾選，編譯後的 .jasper 將會輸出至指定的資料夾中。
        </div>
      </section>
  );
}

/* ───────────── 掃描結果 ───────────── */
function ScanCard({ scan }) {
  if (!scan) return null;
  return (
      <section className="card">
        <h2>檔案掃描結果：共找到 {scan.count} 份報表</h2>
        <div className="scan-list">{scan.count ? scan.files.join("\n") : "（未發現任何 .jrxml 檔案）"}</div>
      </section>
  );
}

/* ───────────── 批次狀態 ───────────── */
function ProgressCard({ status, onCancel, cancelling }) {
  const { state = "IDLE", total = 0, processed = 0, ok = 0, failed = 0, skipped = 0, currentPath, error } = status;
  // 修正原本漏掉的 * 乘號
  const pct = total ? Math.round((processed * 100) / total) : 0;

  let info;
  if (state === "RUNNING") {
    info = "正在背景為您重新編譯報表，請稍候…";
  } else if (state === "FAILED") {
    info = "哎呀，執行過程中斷了，本次未產出完整結果。";
  } else if (state === "CANCELLED") {
    info = skipped > 0
        ? `批次已停止：已處理 ${processed} 份，剩餘 ${skipped} 份未執行。已處理的部分保留，可隨時重跑。`
        : "批次已停止。";
  } else if (state === "DONE") {
    info = failed > 0
        ? `處理完畢！總計 ${total} 份，成功 ${ok} 份、失敗 ${failed} 份。`
        : `太棒了！全部 ${total} 份報表皆順利編譯完成！`;
  } else {
    info = "目前處於待命狀態。點擊「開始執行編譯」即可自動在背景處理。";
  }

  return (
      <section className="card">
        <h2>
          執行進度狀態
          <span className={`state ${state}`}>
          {state === "RUNNING" && <span className="spinner" aria-hidden="true" />}
            {state !== "RUNNING" && <span className="dot" aria-hidden="true" />}
            {STATE_LABEL[state] ?? state}
        </span>
          {state === "RUNNING" && total > 0 && (
              <span className="barinfo">
            已完成 {processed}/{total}（成功 {ok}／失敗 {failed}）
          </span>
          )}
        </h2>
        <div className="bar-row">
          <div className="bar">
            <div style={{ width: `${state === "RUNNING" || total ? pct : 0}%` }} />
          </div>
          <span className="bar-pct">{total ? `${pct}%` : "—"}</span>
        </div>
        <div className="barinfo">{info}</div>
        {state === "RUNNING" && currentPath && (
            <div className="barinfo">目前正在處理：<span className="cur">{currentPath}</span></div>
        )}
        {state === "RUNNING" && (
            <button
                className="btn btn-ghost btn-cancel"
                onClick={onCancel}
                disabled={cancelling}
                title="跑完目前檔案後停止，剩餘檔案標記為未執行（已寫入的 .jasper 保留）">
              {cancelling ? "停止中…" : "⏹ 停止本次執行"}
            </button>
        )}
        {state === "FAILED" && error && <div className="hint job-error">{error}</div>}
      </section>
  );
}

/* ───────────── 失敗明細 ───────────── */
function FailureCard({ report }) {
  if (!report) return null;
  const failures = report.failures ?? [];
  return (
      <section className="card">
        <h2>{failures.length > 0 ? `需要注意的異常檔案（${failures.length}）` : "失敗明細"}</h2>
        {failures.length === 0 ? (
            <div className="empty">✨ 沒有發現失敗，所有報表皆完美編譯成功！</div>
        ) : (
            <>
              <div className="failure-tip">
                ⚠ 下列檔案無法自動轉換，請自行使用 <strong>JasperStudio 7.0.8 以上版本</strong> 開啟重新編譯後存檔。
              </div>
              <table className="failure-table">
              <thead>
              <tr>
                <th>檔案路徑</th>
                <th>停在哪個步驟</th>
                <th>耗時 (ms)</th>
                <th>錯誤原因</th>
              </tr>
              </thead>
              <tbody>
              {failures.map((f, i) => (
                  <tr key={`${f.path}-${i}`}>
                    <td className="path">{f.path}</td>
                    <td className="stage">{f.stage}</td>
                    <td>{f.ms}</td>
                    <td className="err">{f.error}</td>
                  </tr>
              ))}
              </tbody>
            </table>
            </>
        )}
      </section>
  );
}

/* ───────────── Root ───────────── */
export default function App() {
  const [form, setForm] = useState(EMPTY_FORM);
  const [scan, setScan] = useState(null);
  const [scanning, setScanning] = useState(false);
  const [submitting, setSubmitting] = useState(false);
  const [browse, setBrowse] = useState(null); // { key, initial }
  const [cancelling, setCancelling] = useState(false);
  const [build, setBuild] = useState(null); // { version, buildTime, jarVersion }
  const { status, report, error, setError, running, start, clearReport } = useBatchJob();

  // 底部顯示建置版號（寫入於每次前端 build：app/scripts/write-version.js）
  useEffect(() => {
    fetch("/assets/version.json")
        .then((r) => (r.ok ? r.json() : null))
        .then((v) => v && setBuild(v))
        .catch(() => {});
  }, []);

  // 開啟目錄視窗；報告欄位是檔案路徑 → 初始定位到該檔案所在資料夾（純檔名 = 目前目錄）
  const openBrowse = useCallback((key) => {
    let initial = (form[key] || "").trim();
    if (key === "reportFile") {
      const i = Math.max(initial.lastIndexOf("/"), initial.lastIndexOf("\\"));
      initial = i > 0 ? initial.slice(0, i + 1) : "";
    }
    setBrowse({ key, initial });
  }, [form]);

  const handleBrowseSelect = useCallback((dir) => {
    if (!browse) return;
    setForm((f) => {
      if (browse.key === "reportFile") {
        return { ...f, reportFile: dir.replace(/[\\/]+$/, "") + "/recompile-report.json" };
      }
      return { ...f, [browse.key]: dir };
    });
    setBrowse(null);
  }, [browse]);

  // 載入預設設定
  useEffect(() => {
    let alive = true;
    api
        .config()
        .then((c) => {
          if (!alive) return;
          setForm({
            sourceDir: c.sourceDir ?? "",
            outDir: c.outDir ?? "",
            reportFile: c.reportFile ?? "",
            inPlace: !!c.inPlace,
            dryRun: !!c.dryRun,
          });
        })
        .catch((e) => alive && setError(e.message));
    return () => {
      alive = false;
    };
  }, [setError]);

  // 關閉瀏覽器 → 停止 Spring Boot：
  // 頁面開著時每 20s 心跳 POST /api/beat（背景分頁會被瀏覽器限流，但 180s 安全網仍遠大於限流間隔）；
  // pagehide 時 sendBeacon POST /api/close（瀏覽器關閉/導航時最可靠的訊號）。
  useEffect(() => {
    const beat = () => fetch("/api/beat", { method: "POST" }).catch(() => {});
    beat();
    const id = setInterval(beat, 20000);
    const onPageHide = () => {
      try {
        navigator.sendBeacon("/api/close");
      } catch {
        /* 舊瀏覽器無 sendBeacon：交給後端 180s 無活動安全網 */
      }
    };
    window.addEventListener("pagehide", onPageHide);
    return () => {
      clearInterval(id);
      window.removeEventListener("pagehide", onPageHide);
    };
  }, []);

  const handleScan = useCallback(async () => {
    setError("");
    setScanning(true);
    try {
      setScan(await api.scan(form.sourceDir.trim()));
    } catch (e) {
      setError(e.message);
    } finally {
      setScanning(false);
    }
  }, [form.sourceDir, setError]);

  const handleRun = useCallback(async () => {
    const payload = buildPayload(form);
    const effInPlace = payload.inPlace || !payload.outDir;
    if (
        effInPlace &&
        !payload.dryRun &&
        !window.confirm(
            (payload.inPlace
                ? "「就地覆寫」將會直接替換掉來源資料夾中的舊版 .jasper。"
                : "輸出資料夾為空：.jasper 將寫入來源資料夾（與 .jrxml 同位置），替換舊版 .jasper。") +
            "確定要繼續執行嗎？"
        )
    ) {
      return;
    }
    setSubmitting(true);
    setScan(null); // 開始執行 → 收合並清空「檔案掃描結果」卡片
    clearReport(); // 開始執行 → 清除「需要注意的異常檔案」（新執行完成後才重新載入）
    try {
      await start(payload);
    } catch (e) {
      setError(e.message);
    } finally {
      setSubmitting(false);
    }
  }, [form, start, setError, clearReport]);

  const handleDownload = useCallback(() => {
    if (report) downloadJson(report, "recompile-report.json");
  }, [report]);

  // 軟取消：後端在「目前檔案跑完後」停止，剩餘標記 skipped
  const handleCancel = useCallback(async () => {
    setCancelling(true);
    try {
      await api.cancel();
    } catch (e) {
      setError(e.message);
    } finally {
      setCancelling(false);
    }
  }, [setError]);

  const busy = running || submitting;
  const canDownload = useMemo(() => !!report, [report]);

  return (
      <>
        <header className="app-header">
          <h1>UpJasperReport6To7</h1>
          <small>JasperReports 6 升級 7 批次重編譯工具（.jrxml 來源檔案一律唯讀保護）</small>
        </header>
        <main>
          {error && (
              <div className="error-banner" role="alert">
                <span>連線或操作有點小狀況：{error}</span>
                <button onClick={() => setError("")} aria-label="關閉">
                  ×
                </button>
              </div>
          )}
          <ParamsCard
              form={form}
              setForm={setForm}
              busy={busy}
              scanning={scanning}
              onScan={handleScan}
              onRun={handleRun}
              onBrowse={openBrowse}
              canDownload={canDownload}
              onDownload={handleDownload}
          />
          {browse && (
              <DirBrowser
                  title={BROWSE_TITLES[browse.key]}
                  initialPath={browse.initial}
                  onSelect={handleBrowseSelect}
                  onClose={() => setBrowse(null)}
              />
          )}
          <ScanCard scan={scan} />
          <ProgressCard status={status} onCancel={handleCancel} cancelling={cancelling} />
          <FailureCard report={report} />
        </main>
        {build && (
            <footer className="app-footer">
              {build.jarVersion && (
                  <span className="jar">jar {build.jarVersion}</span>
              )}
              <span className="ver">{build.version}</span>
              <span className="btime">build {new Date(build.buildTime).toLocaleString()}</span>
            </footer>
        )}
      </>
  );
}