(() => {
  const STORAGE_KEYS = {
    apiKey: "buscai_api_key",
    deviceId: "buscai_device_id",
  };

  function loadOrCreateDeviceId() {
    let deviceId = localStorage.getItem(STORAGE_KEYS.deviceId);
    if (!deviceId) {
      deviceId = crypto.randomUUID();
      localStorage.setItem(STORAGE_KEYS.deviceId, deviceId);
    }
    return deviceId;
  }

  const state = {
    apiKey: localStorage.getItem(STORAGE_KEYS.apiKey),
    deviceId: loadOrCreateDeviceId(),
  };

  const gateEl = document.getElementById("gate");
  const gateFormEl = document.getElementById("gate-form");
  const gateApiKeyInputEl = document.getElementById("gate-api-key");
  const gateErrorEl = document.getElementById("gate-error");
  const appEl = document.getElementById("app");

  function showGate() {
    gateEl.hidden = false;
    appEl.hidden = true;
    gateApiKeyInputEl.focus();
  }

  function hideGate() {
    gateEl.hidden = true;
    appEl.hidden = false;
  }

  gateFormEl.addEventListener("submit", (event) => {
    event.preventDefault();
    const apiKey = gateApiKeyInputEl.value.trim();
    if (!apiKey) {
      return;
    }
    state.apiKey = apiKey;
    localStorage.setItem(STORAGE_KEYS.apiKey, apiKey);
    gateErrorEl.hidden = true;
    hideGate();
  });

  if (state.apiKey) {
    hideGate();
  } else {
    showGate();
  }
})();
