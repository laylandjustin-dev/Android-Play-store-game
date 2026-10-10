// Models page: list checkpoints, select one for the bot, delete.
"use strict";

const tbody = document.querySelector("#models-table tbody");
const errEl = document.getElementById("models-error");

function render(data) {
  document.getElementById("ckdir").textContent = data.checkpoint_dir;
  document.getElementById("selected").textContent =
    data.selected === null ? "untrained network" : data.selected;
  tbody.textContent = "";
  document.getElementById("no-models").hidden = data.models.length > 0;
  for (const m of data.models) {
    const tr = el("tr", { class: m.selected ? "selected" : null },
      el("td", {}, el("code", { text: m.name }), m.selected ? " ✓" : ""),
      el("td", { class: "num", text: m.iteration ?? "" }),
      el("td", { class: "num", text: m.global_step ?? "" }),
      el("td", { text: m.timestamp || "" }),
      el("td", { class: "num", text: fmtBytes(m.size) }),
      el("td", {},
        el("button", { type: "button", class: "secondary", disabled: m.selected, onclick: () => select(m.name), text: "Use for bot" }),
        " ",
        el("button", { type: "button", class: "danger", onclick: () => remove(m.name), text: "Delete" })));
    tbody.append(tr);
  }
}

async function load() {
  try { showNotice(errEl, null); render(await api("GET", "/api/models")); } catch (e) { showNotice(errEl, e.message); }
}

async function select(name) {
  try { showNotice(errEl, null); render(await api("POST", "/api/models/select", { name })); } catch (e) { showNotice(errEl, e.message); }
}

async function remove(name) {
  if (!confirm(`Delete checkpoint ${name}? This cannot be undone.`)) return;
  try {
    showNotice(errEl, null);
    render(await api("DELETE", `/api/models/${encodeURIComponent(name)}`));
  } catch (e) { showNotice(errEl, e.message); }
}

document.getElementById("use-latest").addEventListener("click", () => select("latest"));
document.getElementById("use-untrained").addEventListener("click", () => select(null));
load();
