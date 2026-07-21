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
    books: [],
    selectedBookIds: null, // null = todos os livros
    conversations: [],
    currentConversationId: null,
    messages: [],
    streaming: false,
  };

  const gateEl = document.getElementById("gate");
  const gateFormEl = document.getElementById("gate-form");
  const gateApiKeyInputEl = document.getElementById("gate-api-key");
  const gateErrorEl = document.getElementById("gate-error");
  const appEl = document.getElementById("app");
  const scopeAllEl = document.getElementById("scope-all");
  const scopeBookListEl = document.getElementById("scope-book-list");
  const conversationListEl = document.getElementById("conversation-list");
  const newConversationBtnEl = document.getElementById("new-conversation-btn");

  function showGate(errorMessage) {
    gateEl.hidden = false;
    appEl.hidden = true;
    if (errorMessage) {
      gateErrorEl.textContent = errorMessage;
      gateErrorEl.hidden = false;
    } else {
      gateErrorEl.hidden = true;
    }
    gateApiKeyInputEl.focus();
  }

  function hideGate() {
    gateEl.hidden = true;
    appEl.hidden = false;
  }

  function apiHeaders() {
    return {
      "X-Api-Key": state.apiKey,
      "X-Device-Id": state.deviceId,
    };
  }

  async function fetchJson(path) {
    const response = await fetch(path, { headers: apiHeaders() });
    if (response.status === 401 || response.status === 403) {
      const error = new Error("chave de acesso inválida");
      error.invalidApiKey = true;
      throw error;
    }
    if (!response.ok) {
      throw new Error(`falha ao chamar ${path}: ${response.status}`);
    }
    return response.json();
  }

  function handleInvalidApiKey() {
    state.apiKey = null;
    localStorage.removeItem(STORAGE_KEYS.apiKey);
    showGate("Chave de acesso inválida. Informe a chave novamente.");
  }

  async function loadCatalogAndConversations() {
    try {
      const [books, conversations] = await Promise.all([
        fetchJson("/books"),
        fetchJson("/conversations"),
      ]);
      state.books = books;
      state.conversations = conversations;
      renderBookList();
      renderConversationList();
    } catch (error) {
      if (error.invalidApiKey) {
        handleInvalidApiKey();
      } else {
        console.error(error);
      }
    }
  }

  function renderBookList() {
    scopeBookListEl.innerHTML = "";
    for (const book of state.books) {
      const li = document.createElement("li");
      const label = document.createElement("label");
      const checkbox = document.createElement("input");
      checkbox.type = "checkbox";
      checkbox.checked = state.selectedBookIds === null || state.selectedBookIds.has(book.id);
      checkbox.disabled = state.selectedBookIds === null;
      checkbox.addEventListener("change", () => onBookCheckboxChange(book.id, checkbox.checked));
      label.appendChild(checkbox);
      label.append(" " + book.title);
      li.appendChild(label);
      scopeBookListEl.appendChild(li);
    }
  }

  function onBookCheckboxChange(bookId, checked) {
    if (state.selectedBookIds === null) {
      state.selectedBookIds = new Set(state.books.map((book) => book.id));
    }
    if (checked) {
      state.selectedBookIds.add(bookId);
    } else {
      state.selectedBookIds.delete(bookId);
    }
    renderBookList();
  }

  scopeAllEl.addEventListener("change", () => {
    state.selectedBookIds = scopeAllEl.checked ? null : new Set(state.books.map((book) => book.id));
    renderBookList();
  });

  function renderConversationList() {
    conversationListEl.innerHTML = "";
    for (const conversation of state.conversations) {
      const li = document.createElement("li");
      li.dataset.conversationId = conversation.id;
      li.className = conversation.id === state.currentConversationId ? "active" : "";
      li.textContent = new Date(conversation.createdAt).toLocaleString("pt-BR", {
        dateStyle: "short",
        timeStyle: "short",
      });
      conversationListEl.appendChild(li);
    }
  }

  newConversationBtnEl.addEventListener("click", () => {
    state.currentConversationId = null;
    state.messages = [];
    renderConversationList();
  });

  gateFormEl.addEventListener("submit", (event) => {
    event.preventDefault();
    const apiKey = gateApiKeyInputEl.value.trim();
    if (!apiKey) {
      return;
    }
    state.apiKey = apiKey;
    localStorage.setItem(STORAGE_KEYS.apiKey, apiKey);
    hideGate();
    loadCatalogAndConversations();
  });

  if (state.apiKey) {
    hideGate();
    loadCatalogAndConversations();
  } else {
    showGate();
  }
})();
