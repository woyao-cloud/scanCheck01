package com.example.compliance.result.engine

import org.springframework.stereotype.Component
import java.util.concurrent.ConcurrentHashMap

interface EngineAdapterRegistry {
    fun register(adapter: ScanEngineAdapter)
    fun get(engine: String): ScanEngineAdapter?
    fun engines(): Set<String>
}

@Component
class DefaultEngineAdapterRegistry(
    adapters: List<ScanEngineAdapter>,
) : EngineAdapterRegistry {
    private val registry = ConcurrentHashMap<String, ScanEngineAdapter>()

    init {
        adapters.forEach { registry[it.engine.uppercase()] = it }
    }

    override fun register(adapter: ScanEngineAdapter) {
        registry[adapter.engine.uppercase()] = adapter
    }

    override fun get(engine: String): ScanEngineAdapter? = registry[engine.uppercase()]

    override fun engines(): Set<String> = registry.keys
}
