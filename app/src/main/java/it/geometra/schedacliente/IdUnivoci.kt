package it.geometra.schedacliente

import java.util.concurrent.atomic.AtomicLong

/**
 * Generatore di ID univoci per clienti, progetti planimetria e bollette.
 *
 * Prima l'ID era semplicemente System.currentTimeMillis(): su molti telefoni l'orologio avanza
 * a scatti di 10-16 ms, quindi due salvataggi ravvicinati (doppio tap su Salva, o due conflitti
 * d'importazione risolti di fila con "Tieni entrambe") potevano ottenere lo stesso ID e
 * sovrascriversi a vicenda.
 *
 * Qui l'ID resta "a forma di orario" (positivo e crescente, compatibile con gli ID già salvati
 * e con l'uso di -1 come "nessun ID" e dell'ID come nome di cartella), ma:
 *  - all'interno dell'app è strettamente crescente: ogni nuovo ID è almeno l'ultimo + 1,
 *    anche se l'orologio non è ancora avanzato;
 *  - se si passa l'insieme degli ID già presenti nell'archivio, non ne restituisce mai uno
 *    già usato (utile per i record importati da un altro telefono con l'orologio avanti).
 */
object IdUnivoci {

    private val ultimo = AtomicLong(0L)

    fun nuovo(esistenti: Set<Long> = emptySet()): Long {
        while (true) {
            val precedente = ultimo.get()
            val candidato = maxOf(System.currentTimeMillis(), precedente + 1)
            if (!ultimo.compareAndSet(precedente, candidato)) continue
            if (candidato !in esistenti) return candidato
            // ID già usato nell'archivio: si riprova, il prossimo sarà comunque maggiore.
        }
    }
}
