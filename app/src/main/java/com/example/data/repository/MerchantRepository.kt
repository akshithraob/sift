package com.example.data.repository

import android.content.Context
import com.example.data.model.MerchantGuide
import org.json.JSONArray

class MerchantRepository(private val context: Context) {

    private val cachedGuides = mutableListOf<MerchantGuide>()

    init {
        loadGuides()
    }

    private fun loadGuides() {
        try {
            val jsonString = context.assets.open("merchants.json").bufferedReader().use { it.readText() }
            val jsonArray = JSONArray(jsonString)
            for (i in 0 until jsonArray.length()) {
                val obj = jsonArray.getJSONObject(i)
                val aliasesJson = obj.getJSONArray("aliases")
                val aliases = mutableListOf<String>()
                for (j in 0 until aliasesJson.length()) {
                    aliases.add(aliasesJson.getString(j))
                }
                cachedGuides.add(
                    MerchantGuide(
                        id = obj.getString("id"),
                        aliases = aliases,
                        name = obj.getString("name"),
                        category = obj.getString("category"),
                        cancellationUrl = obj.getString("cancellationUrl"),
                        instructions = obj.getString("instructions"),
                        supportContact = obj.getString("supportContact")
                    )
                )
            }
        } catch (_: Exception) {
        }
    }

    fun findGuideForMerchant(merchantName: String): MerchantGuide? {
        val lower = merchantName.lowercase()
        return cachedGuides.firstOrNull { guide ->
            lower.contains(guide.name.lowercase()) || guide.aliases.any { lower.contains(it.lowercase()) }
        }
    }

    fun getAllGuides(): List<MerchantGuide> = cachedGuides
}
