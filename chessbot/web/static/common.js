// Shared helpers for all pages (no external dependencies).
"use strict";

async function api(method, url, body) {
  const opts = { method, headers: {} };
  if (body !== undefined) {
    opts.headers["Content-Type"] = "application/json";
    opts.body = JSON.stringify(body);
  }
  const resp = await fetch(url, opts);
  let data = null;
  try { data = await resp.json(); } catch (e) { data = null; }
  if (!resp.ok) {
    const err = new Error((data && (data.error || (data.errors && data.errors.join("; ")))) || `HTTP ${resp.status}`);
    err.status = resp.status;
    err.data = data;
    throw err;
  }
  return data;
}

function el(tag, attrs, ...children) {
  const node = document.createElement(tag);
  for (const [k, v] of Object.entries(attrs || {})) {
    if (v === null || v === undefined || v === false) continue;
    if (k === "class") node.className = v;
    else if (k === "text") node.textContent = v;
    else if (k.startsWith("on")) node.addEventListener(k.slice(2), v);
    else node.setAttribute(k, v === true ? "" : v);
  }
  for (const c of children) {
    if (c === null || c === undefined) continue;
    node.append(c instanceof Node ? c : document.createTextNode(String(c)));
  }
  return node;
}

function showNotice(node, message) {
  if (!node) return;
  if (!message) { node.hidden = true; node.textContent = ""; return; }
  node.hidden = false;
  node.textContent = message;
}

function fmtBytes(n) {
  if (n === null || n === undefined) return "";
  const units = ["B", "KB", "MB", "GB"];
  let i = 0; let v = n;
  while (v >= 1024 && i < units.length - 1) { v /= 1024; i++; }
  return `${v.toFixed(i ? 1 : 0)} ${units[i]}`;
}

function fmtNum(v) {
  if (v === null || v === undefined || v === "") return "";
  if (typeof v !== "number") return String(v);
  if (Number.isInteger(v)) return v.toLocaleString();
  const a = Math.abs(v);
  if (a !== 0 && (a < 0.001 || a >= 1e6)) return v.toExponential(2);
  return v.toFixed(a < 1 ? 4 : 2);
}
