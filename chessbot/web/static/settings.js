// Settings page: form generated from /api/config/schema, server-side validation.
"use strict";

const sectionsEl = document.getElementById("sections");
const formErrEl = document.getElementById("form-errors");
const formOkEl = document.getElementById("form-ok");
const form = document.getElementById("settings-form");
let schema = null;

function inputFor(f, value) {
  const id = "f-" + f.key.replace(/\./g, "-");
  if (f.type === "bool") {
    return el("input", { type: "checkbox", id, name: f.key, checked: !!value });
  }
  if (f.choices) {
    const sel = el("select", { id, name: f.key });
    for (const c of f.choices) sel.append(el("option", { value: c, text: c, selected: c === value }));
    return sel;
  }
  const attrs = { id, name: f.key, value: value === null || value === undefined ? "" : String(value) };
  if (f.type === "int") Object.assign(attrs, { type: "number", step: "1" });
  else if (f.type === "float") Object.assign(attrs, { type: "number", step: "any" });
  else attrs.type = "text";
  if (f.nullable) attrs.placeholder = "blank = null";
  return el("input", attrs);
}

function render() {
  sectionsEl.textContent = "";
  for (const group of schema.sections) {
    const fs = el("fieldset", {}, el("legend", { text: group.section }));
    for (const f of group.fields) {
      const input = inputFor(f, f.value);
      const typeText = f.type + (f.nullable ? " or null" : "") + ` · default ${f.default === null ? "null" : f.default}`;
      fs.append(el("div", { class: "field", "data-key": f.key },
        el("label", { for: input.id, class: "name", text: f.key }),
        input,
        el("div", { class: "help" }, f.help, el("br"), el("span", { class: "small", text: typeText })),
        el("div", { class: "ferr", hidden: true })));
    }
    sectionsEl.append(fs);
  }
}

function fillValues(useDefaults) {
  for (const group of schema.sections) for (const f of group.fields) {
    const input = form.elements[f.key];
    const v = useDefaults ? f.default : f.value;
    if (f.type === "bool") input.checked = !!v;
    else input.value = v === null || v === undefined ? "" : String(v);
  }
}

function collect() {
  const out = {};
  for (const group of schema.sections) for (const f of group.fields) {
    const input = form.elements[f.key];
    let v;
    if (f.type === "bool") v = input.checked;
    else {
      const raw = input.value.trim();
      if (raw === "" && f.nullable) v = null;
      else if (f.type === "int" || f.type === "float") {
        // Send unparsable text as-is so the server reports a proper type error.
        v = raw !== "" && isFinite(Number(raw)) ? Number(raw) : raw;
      } else v = raw;
    }
    if (f.key === "run_dir") out.run_dir = v;
    else {
      const [sec, name] = f.key.split(".");
      (out[sec] = out[sec] || {})[name] = v;
    }
  }
  return out;
}

function clearErrors() {
  showNotice(formErrEl, null);
  showNotice(formOkEl, null);
  for (const node of form.querySelectorAll(".field")) {
    node.classList.remove("invalid");
    const fe = node.querySelector(".ferr"); fe.hidden = true; fe.textContent = "";
  }
}

function showErrors(errors) {
  const general = [];
  for (const msg of errors) {
    const key = msg.split(":")[0].trim();
    const node = form.querySelector(`.field[data-key="${CSS.escape(key)}"]`);
    if (node) {
      node.classList.add("invalid");
      const fe = node.querySelector(".ferr");
      fe.hidden = false;
      fe.textContent = (fe.textContent ? fe.textContent + "; " : "") + msg.slice(key.length + 1).trim();
    } else general.push(msg);
  }
  formErrEl.hidden = false;
  formErrEl.textContent = "";
  formErrEl.append(el("strong", { text: `Not saved: ${errors.length} problem(s).` }));
  const ul = el("ul");
  for (const m of general.length ? general : errors) ul.append(el("li", { text: m }));
  formErrEl.append(ul);
  const first = form.querySelector(".field.invalid input, .field.invalid select");
  if (first) first.focus();
}

form.addEventListener("submit", async (ev) => {
  ev.preventDefault();
  clearErrors();
  try {
    const res = await api("POST", "/api/config", collect());
    await load();
    showNotice(formOkEl, res.saved ? `Saved to ${res.path} and applied.` : "Applied to this server session (not persisted to a file).");
  } catch (e) {
    if (e.data && Array.isArray(e.data.errors)) showErrors(e.data.errors);
    else showNotice(formErrEl, e.message);
  }
});

document.getElementById("reload").addEventListener("click", () => { clearErrors(); fillValues(false); });
document.getElementById("defaults").addEventListener("click", () => {
  clearErrors(); fillValues(true);
  showNotice(formOkEl, "Default values filled in; press Save to apply them.");
});

async function load() {
  schema = await api("GET", "/api/config/schema");
  render();
}

load().catch((e) => showNotice(formErrEl, e.message));
