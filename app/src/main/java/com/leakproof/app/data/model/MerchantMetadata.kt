package com.leakproof.app.data.model

data class MerchantGuide(
    val id: String,
    val aliases: List<String>,
    val name: String,
    val category: String,
    val cancellationUrl: String,
    val instructions: String,
    val supportContact: String
)
