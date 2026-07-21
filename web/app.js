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
  const chatFormEl = document.getElementById("chat-form");
  const chatInputEl = document.getElementById("chat-input");
  const chatSubmitEl = chatFormEl.querySelector("button[type=submit]");
  const messageListEl = document.getElementById("message-list");
  const chatStatusEl = document.getElementById("chat-status");
  const appStatusEl = document.getElementById("app-status");

  const GENERIC_STREAM_ERROR_MESSAGE =
    "Ocorreu um erro ao gerar a resposta. Tente novamente em instantes.";
  const GENERIC_LOAD_ERROR_MESSAGE =
    "Não foi possível carregar os dados agora. Tente novamente em instantes.";

  // Incrementado a cada troca de conversa/nova conversa: uma resposta de fetch de uma
  // navegação já superada por uma ação mais recente do usuário se identifica e é descartada
  // (evita que a resposta de um clique antigo sobrescreva o resultado de um clique mais novo).
  let navigationToken = 0;

  function setAppStatus(text, { isError = false } = {}) {
    if (!text) {
      appStatusEl.hidden = true;
      appStatusEl.classList.remove("error");
      return;
    }
    appStatusEl.textContent = text;
    appStatusEl.classList.toggle("error", isError);
    appStatusEl.hidden = false;
  }

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
    setAppStatus("Carregando livros e conversas...");
    try {
      const [books, conversations] = await Promise.all([
        fetchJson("/books"),
        fetchJson("/conversations"),
      ]);
      state.books = books;
      state.conversations = conversations;
      renderBookList();
      renderConversationList();
      setAppStatus(null);
    } catch (error) {
      if (error.invalidApiKey) {
        handleInvalidApiKey();
      } else {
        console.error(error);
        setAppStatus(GENERIC_LOAD_ERROR_MESSAGE, { isError: true });
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
    if (state.streaming) {
      return;
    }
    navigationToken += 1;
    setAppStatus(null);
    state.currentConversationId = null;
    state.messages = [];
    renderConversationList();
    renderMessages();
  });

  conversationListEl.addEventListener("click", (event) => {
    const li = event.target.closest("li[data-conversation-id]");
    if (!li || state.streaming) {
      return;
    }
    openConversation(li.dataset.conversationId);
  });

  async function openConversation(conversationId) {
    const token = ++navigationToken;
    setAppStatus("Carregando conversa...");
    try {
      const detail = await fetchJson(`/conversations/${conversationId}`);
      if (token !== navigationToken) {
        // uma acao de navegacao mais recente (outra conversa, ou "nova conversa") ja aconteceu
        return;
      }
      state.currentConversationId = detail.id;
      state.messages = detail.messages.map((message) => ({
        role: message.role,
        content: message.content,
      }));
      renderConversationList();
      renderMessages();
      setAppStatus(null);
    } catch (error) {
      if (token !== navigationToken) {
        return;
      }
      if (error.invalidApiKey) {
        handleInvalidApiKey();
      } else {
        console.error(error);
        setAppStatus(GENERIC_LOAD_ERROR_MESSAGE, { isError: true });
      }
    }
  }

  async function loadConversationsOnly() {
    try {
      state.conversations = await fetchJson("/conversations");
      renderConversationList();
    } catch (error) {
      if (error.invalidApiKey) {
        handleInvalidApiKey();
      } else {
        console.error(error);
        setAppStatus(GENERIC_LOAD_ERROR_MESSAGE, { isError: true });
      }
    }
  }

  function renderMessages() {
    messageListEl.innerHTML = "";
    for (const message of state.messages) {
      if (message.role === "ASSISTANT" && message.content === "") {
        continue;
      }
      const bubble = document.createElement("div");
      bubble.className = "message message-" + message.role.toLowerCase();
      bubble.textContent = message.content;
      messageListEl.appendChild(bubble);
    }
    messageListEl.scrollTop = messageListEl.scrollHeight;
  }

  function setChatStatus(text) {
    if (text) {
      chatStatusEl.textContent = text;
      chatStatusEl.hidden = false;
    } else {
      chatStatusEl.hidden = true;
    }
  }

  function setStreaming(streaming) {
    state.streaming = streaming;
    chatInputEl.disabled = streaming;
    chatSubmitEl.disabled = streaming;
  }

  async function consumeChatStream(bodyStream, assistantMessage) {
    const reader = bodyStream.getReader();
    const decoder = new TextDecoder();
    let buffer = "";
    let currentEvent = null;
    let currentDataLines = [];

    function dispatch(eventName, data) {
      if (eventName === "conversation") {
        const isNewConversation = state.currentConversationId === null;
        state.currentConversationId = data;
        if (isNewConversation) {
          loadConversationsOnly();
        }
        return;
      }
      if (eventName === "token") {
        if (!state.messages.includes(assistantMessage)) {
          state.messages.push(assistantMessage);
        }
        assistantMessage.content += data;
        setChatStatus(null);
        renderMessages();
        return;
      }
      if (eventName === "error") {
        assistantMessage.content = data;
        if (!state.messages.includes(assistantMessage)) {
          state.messages.push(assistantMessage);
        }
        setChatStatus(null);
        renderMessages();
      }
    }

    while (true) {
      const { value, done } = await reader.read();
      if (done) {
        break;
      }
      buffer += decoder.decode(value, { stream: true });
      const lines = buffer.split("\n");
      buffer = lines.pop();

      for (const line of lines) {
        if (line === "") {
          if (currentEvent !== null) {
            dispatch(currentEvent, currentDataLines.join("\n"));
          }
          currentEvent = null;
          currentDataLines = [];
          continue;
        }
        if (line.startsWith("event:")) {
          currentEvent = line.slice("event:".length).trim();
        } else if (line.startsWith("data:")) {
          // Só o espaço logo após "data:" é delimitador (protocolo SSE) — o resto do
          // conteúdo, incluindo espaços à direita, é o próprio delta de texto do token
          // e precisa ser preservado, senão a concatenação perde espaços entre palavras.
          let value = line.slice("data:".length);
          if (value.startsWith(" ")) {
            value = value.slice(1);
          }
          currentDataLines.push(value);
        }
      }
    }
  }

  chatFormEl.addEventListener("submit", async (event) => {
    event.preventDefault();
    const query = chatInputEl.value.trim();
    if (!query || state.streaming) {
      return;
    }
    chatInputEl.value = "";
    state.messages.push({ role: "USER", content: query });
    renderMessages();

    setStreaming(true);
    setChatStatus("Aguardando resposta...");

    const assistantMessage = { role: "ASSISTANT", content: "" };

    try {
      const response = await fetch("/chat", {
        method: "POST",
        headers: {
          ...apiHeaders(),
          "Content-Type": "application/json",
        },
        body: JSON.stringify({
          conversationId: state.currentConversationId,
          query,
          bookIds: state.selectedBookIds === null ? null : Array.from(state.selectedBookIds),
        }),
      });

      if (response.status === 401 || response.status === 403) {
        handleInvalidApiKey();
        return;
      }
      if (!response.ok || !response.body) {
        throw new Error(`falha ao iniciar o chat: ${response.status}`);
      }

      await consumeChatStream(response.body, assistantMessage);
    } catch (error) {
      console.error(error);
      if (!state.messages.includes(assistantMessage)) {
        state.messages.push(assistantMessage);
      }
      assistantMessage.content = assistantMessage.content
        ? assistantMessage.content + "\n\n" + GENERIC_STREAM_ERROR_MESSAGE
        : GENERIC_STREAM_ERROR_MESSAGE;
      renderMessages();
    } finally {
      setStreaming(false);
      setChatStatus(null);
    }
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
