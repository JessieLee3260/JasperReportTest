/**
 * 後端 API 封裝：錯誤轉成白話訊息，避免把 raw JSON parse error 直接丟給使用者。
 */
async function request(path, options) {
  let res;
  try {
    res = await fetch(path, options);
  } catch {
    throw new Error("連不到後端服務。請確認 Spring Boot 有在跑（正式使用直接開 http://localhost:31415）");
  }

  if (!res.ok) {
    const text = await res.text().catch(() => "");
    throw new Error(`後端回傳 ${res.status}。${text ? text.slice(0, 300) : ""}`);
  }

  try {
    return await res.json();
  } catch {
    throw new Error("後端回傳的內容不是 JSON（可能開到錯的頁面，或後端還沒跑起來）");
  }
}

export const api = {
  config: () => request("/api/config"),
  browse: (path) =>
    request("/api/browse?" + new URLSearchParams(path ? { path } : {})),
  scan: (sourceDir) =>
    request("/api/scan?" + new URLSearchParams(sourceDir ? { sourceDir } : {})),
  run: (payload) =>
    request("/api/run", {
      method: "POST",
      headers: { "Content-Type": "application/json" },
      body: JSON.stringify(payload),
    }),
  status: () => request("/api/status"),
  report: () => request("/api/report"),
  cancel: () => request("/api/cancel", { method: "POST" }),
};
