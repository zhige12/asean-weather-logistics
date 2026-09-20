import { createApp } from "vue";
import axios from "axios";
import App from "./App.vue";
import "leaflet/dist/leaflet.css";

// Capacitor 本地服务器有 SPA fallback 行为：请求 /api/* 等不存在的路径
// 会返回 index.html (200 OK) 而不是 404。axios 拦截器检测到 HTML
// 响应就转为 rejected，避免把 HTML 字符串当数据用导致 .map/.reduce 报错。
axios.interceptors.response.use(
  function (response) {
    const ct = String(response.headers["content-type"] || "");
    if (ct.includes("text/html")) {
      return Promise.reject(
        new Error("Capacitor HTML fallback for " + (response.config && response.config.url))
      );
    }
    return response;
  },
  function (error) {
    return Promise.reject(error);
  }
);

// 捕获所有未处理的错误，显示在页面上，避免 WebView 白屏
const errDiv = document.createElement("div");
errDiv.id = "error-overlay";
errDiv.style.cssText =
  "display:none;position:fixed;inset:0;z-index:99999;background:#0a0a1a;color:#f55;font-family:monospace;padding:20px;overflow:auto;font-size:12px;line-height:1.7;white-space:pre-wrap;";
document.body.appendChild(errDiv);

function showError(label, msg) {
  errDiv.style.display = "block";
  const line = document.createElement("div");
  line.style.cssText =
    "margin-bottom:8px;padding:8px;background:rgba(255,0,0,0.08);border-left:3px solid #f55;";
  line.textContent =
    "[" + new Date().toLocaleTimeString() + "] " + label + ": " + msg;
  errDiv.appendChild(line);
}

window.addEventListener("error", function (e) {
  // 原实现要求 filename 含 "assets/main"，开发模式下文件名是 /src/main.js 之类，
  // 过滤会把错误全部吞掉 —— 恰恰在最需要它的时候失效。这里去掉该过滤。
  showError(
    "JS ERROR",
    e.message + " @ " + String(e.filename || "").split("/").pop() + ":" + e.lineno
  );
});
window.addEventListener("unhandledrejection", function (e) {
  showError(
    "PROMISE",
    String((e.reason && e.reason.message) || e.reason)
  );
});

const app = createApp(App);

app.config.errorHandler = function (err, instance, info) {
  showError("VUE ERROR", String(err) + " | " + (info || ""));
  console.error(err, info);
};

app.mount("#app");