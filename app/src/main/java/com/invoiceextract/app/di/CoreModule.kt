package com.invoiceextract.app.di

import org.koin.core.module.Module
import org.koin.dsl.module

/**
 * The core Koin module.
 *
 * Phase 1 intentionally ships an empty graph: the :app module only contains the
 * Compose shell (theme, navigation, placeholder screens), which has no dependencies
 * to inject yet. Concrete bindings for the OCR engine, the AI extractor, the local
 * database and the view models are appended to this module in later phases, keeping
 * a single declaration site for the whole object graph.
 *
 * Declared as a `val` list so additional modules can be composed at start-up:
 *
 * ```
 * startKoin { modules(coreModules + ocrModules) }
 * ```
 */
val coreModule: Module = module { }

/** All Koin modules owned by the application shell. */
val coreModules: List<Module> = listOf(coreModule)
