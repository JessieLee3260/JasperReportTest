import { useCallback, useEffect, useState } from "react";
import { api } from "../api";

const POLL_MS = 2000;
const isFinished = (state) => state === "DONE" || state === "FAILED" || state === "CANCELLED";

/**
 * 管理批次狀態、報告與輪詢。
 * - 以 setTimeout 串接輪詢，避免上一個請求未完成時重複發送
 * - 元件卸載時自動停止
 */
export function useBatchJob() {
  const [status, setStatus] = useState({ state: "IDLE" });
  const [report, setReport] = useState(null);
  const [error, setError] = useState("");

  const loadReport = useCallback(async () => {
    try {
      setReport(await api.report());
    } catch {
      setReport(null);
    }
  }, []);

  // 首次載入：若已有進行中／已完成的批次，直接還原畫面
  useEffect(() => {
    let alive = true;
    (async () => {
      try {
        const s = await api.status();
        if (!alive) return;
        setStatus(s);
        if (isFinished(s.state)) await loadReport();
      } catch (e) {
        if (alive) setError(e.message);
      }
    })();
    return () => {
      alive = false;
    };
  }, [loadReport]);

  // RUNNING 時才輪詢
  const running = status.state === "RUNNING";
  useEffect(() => {
    if (!running) return;
    let alive = true;
    let timer;

    const tick = async () => {
      try {
        const s = await api.status();
        if (!alive) return;
        setStatus(s);
        if (s.state !== "RUNNING") {
          if (isFinished(s.state)) await loadReport();
          return;
        }
      } catch (e) {
        if (alive) setError(e.message);
      }
      if (alive) timer = setTimeout(tick, POLL_MS);
    };

    timer = setTimeout(tick, POLL_MS);
    return () => {
      alive = false;
      clearTimeout(timer);
    };
  }, [running, loadReport]);

  const start = useCallback(async (payload) => {
    setError("");
    setReport(null);
    await api.run(payload);
    setStatus(await api.status());
  }, []);

  /** 立即清空「需要注意的異常檔案」（新執行完成後才會重新載入新報告）。 */
  const clearReport = useCallback(() => setReport(null), []);

  return { status, report, error, setError, running, start, clearReport };
}
