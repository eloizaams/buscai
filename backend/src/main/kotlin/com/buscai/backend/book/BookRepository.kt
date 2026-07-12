package com.buscai.backend.book

import org.springframework.data.jpa.repository.JpaRepository

interface BookRepository : JpaRepository<Book, String>
