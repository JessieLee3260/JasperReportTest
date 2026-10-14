import { useCallback, useEffect, useState } from "react";
import { api } from "../api";

/**
 * 目錄視窗（directory browser）：挑選伺服器端目錄的 modal。
 * 瀏覽器拿不到 server 端檔案系統的絕對路徑，故透過 /api/browse 回傳
 * 「目前目錄」的子目錄清單：點行進入、「..」上層、底部快速起點跳 home／磁碟根。
 */
export default function DirBrowser({ title, initialPath, onSelect, onClose }) {
  const [data, setData] = useState(null); // 目前目錄 { path, parent, dirs, roots, home }
  const [roots, setRoots] = useState([]); // 快速起點：開啟時獨立抓一次，初始載入失敗仍可跳轉
  const [err, setErr] = useState("");
  const [loading, setLoading] = useState(true);

  const load = useCallback(async (path) => {
    setLoading(true);
    setErr("");
    try {
      setData(await api.browse(path));
    } catch (e) {
      setErr(e.message);
    } finally {
      setLoading(false);
    }
  }, []);

  useEffect(() => {
    load(initialPath || "");
    api.browse("").then((d) => setRoots(d.roots)).catch(() => {});
  }, [initialPath, load]);

  useEffect(() => {
    const onKey = (e) => {
      if (e.key === "Escape") onClose();
    };
    window.addEventListener("keydown", onKey);
    return () => window.removeEventListener("keydown", onKey);
  }, [onClose]);

  const current = data ? data.path : (initialPath || "");

  return (
    <div className="modal-mask" onClick={onClose} role="dialog" aria-modal="true" aria-label={title}>
      <div className="modal" onClick={(e) => e.stopPropagation()}>
        <div className="modal-title">
          <h3>{title}</h3>
          <button className="modal-close" onClick={onClose} aria-label="關閉">×</button>
        </div>
        <div className="modal-path" title={current}>{current || "（未取得路徑）"}</div>
        <div className="modal-rows">
          {loading && <div className="modal-hint">載入中…</div>}
          {!loading && err && <div className="modal-hint error">{err}</div>}
          {!loading && !err && data && (
            <>
              {data.parent && (
                <button className="modal-row" onClick={() => load(data.parent)} title={data.parent}>
                  ⌂ ..（上一層）
                </button>
              )}
              {data.dirs.map((d) => (
                <button key={d} className="modal-row" onClick={() => load(d)} title={d}>
                  📁 {d}
                </button>
              ))}
              {data.dirs.length === 0 && <div className="modal-hint">（沒有子資料夾）</div>}
            </>
          )}
        </div>
        <div className="modal-roots">
          {roots.map((r) => (
            <button key={r} className="chip" onClick={() => load(r)} title={r}>{r}</button>
          ))}
        </div>
        <div className="modal-actions">
          <button className="btn btn-primary" disabled={loading || !data} onClick={() => onSelect(current)}>
            ✅ 選取此目錄
          </button>
          <button className="btn btn-ghost" onClick={onClose}>取消</button>
        </div>
      </div>
    </div>
  );
}
