// Prepares photos picked in an image parameter before the build form is submitted.
// The Jenkins server can't decode HEIC (the iPhone default), so HEIC photos are converted to JPEG here
// with heic-to, which also applies the photo's rotation. The ~3 MB decoder only loads when it's needed.

const HEIC_MIME_TYPES = new Set(["image/heic", "image/heif", "image/heic-sequence", "image/heif-sequence"]);
const HEIC_EXTENSION = /\.(heic|heif)$/i;
const SUBMIT_BUTTONS = 'button[type="submit"], button:not([type]), input[type="submit"]';

let heicToModule;
const loadHeicTo = () => (heicToModule ??= import("./vendor/heic-to.js"));

// Tracks the latest selection per input, so a slow conversion can't overwrite a newer choice.
const latestSelection = new WeakMap();
// Counts conversions in progress per form, so submit stays disabled until all of them finish.
const pendingConversions = new WeakMap();

const mightBeHeic = (file) => HEIC_MIME_TYPES.has(file.type.toLowerCase()) || HEIC_EXTENSION.test(file.name);

function parts(input) {
  const container = input.closest(".jenkins-file-parameter");
  return {
    status: container?.querySelector(".jenkinsocr-image-status"),
    preview: container?.querySelector(".jenkinsocr-image-preview"),
  };
}

function showStatus(input, message) {
  const { status } = parts(input);
  if (!status) {
    return;
  }
  status.textContent = message ?? "";
  status.hidden = !message;
}

function showPreview(input, file) {
  const { preview } = parts(input);
  if (!preview) {
    return;
  }
  if (preview.src.startsWith("blob:")) {
    URL.revokeObjectURL(preview.src);
  }
  if (file) {
    preview.src = URL.createObjectURL(file);
    preview.style.display = "block";
  } else {
    preview.removeAttribute("src");
    preview.style.display = "none";
  }
}

function setConverting(form, converting) {
  if (!form) {
    return;
  }
  const count = (pendingConversions.get(form) ?? 0) + (converting ? 1 : -1);
  pendingConversions.set(form, count);
  for (const button of form.querySelectorAll(SUBMIT_BUTTONS)) {
    button.disabled = count > 0;
  }
}

function replaceFile(input, file) {
  const transfer = new DataTransfer();
  transfer.items.add(file);
  input.files = transfer.files;
}

async function convertHeic(file) {
  const { heicTo, isHeic } = await loadHeicTo();
  if (!(await isHeic(file))) {
    return file;
  }
  const jpeg = await heicTo({ blob: file, type: "image/jpeg", quality: 0.92 });
  const name = file.name.replace(/\.[^.]*$/, "") + ".jpg";
  return new File([jpeg], name, { type: "image/jpeg", lastModified: file.lastModified });
}

async function handleSelection(input) {
  const file = input.files?.[0];
  const selection = Symbol("selection");
  latestSelection.set(input, selection);
  showStatus(input, null);

  if (!file || !mightBeHeic(file)) {
    showPreview(input, file);
    return;
  }

  showPreview(input, null);
  showStatus(input, "Converting HEIC photo to JPEG…");
  setConverting(input.form, true);
  try {
    const converted = await convertHeic(file);
    if (latestSelection.get(input) !== selection) {
      return;
    }
    replaceFile(input, converted);
    showStatus(input, null);
    showPreview(input, converted);
  } catch (error) {
    if (latestSelection.get(input) !== selection) {
      return;
    }
    console.error("Failed to convert HEIC photo", error);
    input.value = "";
    showStatus(input, "This HEIC photo couldn't be converted. Export it as JPEG and upload that instead.");
  } finally {
    setConverting(input.form, false);
  }
}

// Delegated so it also covers parameter forms rendered after this module loads.
document.addEventListener("change", (event) => {
  const input = event.target;
  if (input instanceof HTMLInputElement && input.dataset.jenkinsocrImageInput) {
    handleSelection(input);
  }
});
