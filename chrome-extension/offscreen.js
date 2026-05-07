chrome.runtime.onMessage.addListener((message, sender, sendResponse) => {
  if (message?.target !== "offscreen" || message.type !== "copy-text") {
    return false;
  }

  try {
    const target = document.getElementById("clipboard-target");
    target.value = message.text || "";
    target.focus();
    target.select();

    const ok = document.execCommand("copy");
    sendResponse({ ok });
  } catch (error) {
    sendResponse({ ok: false, error: error.message });
  }

  return false;
});

