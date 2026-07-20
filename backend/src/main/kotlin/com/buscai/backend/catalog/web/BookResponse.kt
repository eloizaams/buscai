package com.buscai.backend.catalog.web

/** DTO de resposta de `GET /books` (`specs/cliente-web/plan.md`, seção "Contratos entre camadas"). */
data class BookResponse(
    val id: String,
    val title: String,
)
