package com.buscai.backend.catalog

import org.springframework.data.jpa.repository.JpaRepository

interface BookRepository : JpaRepository<Book, String>
