package com.aeoncorex.streamx.data

import com.aeoncorex.streamx.model.Movie
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.Query
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.tasks.await

object MovieRepository {
    private val db = FirebaseFirestore.getInstance()

    // [REAL-TIME] Firestore Listener to Flow
    // এটি লাইভ ডাটা স্ট্রিম করবে
    fun getMoviesFlow(): Flow<List<Movie>> = callbackFlow {
        val listener = db.collection("movies")
            .orderBy("createdAt", Query.Direction.DESCENDING) // নতুন মুভি আগে দেখাবে
            .addSnapshotListener { snapshot, error ->
                if (error != null) {
                    close(error)
                    return@addSnapshotListener
                }
                
                val movies = snapshot?.documents?.mapNotNull { doc ->
                    doc.toObject(Movie::class.java)?.copy(id = doc.id)
                } ?: emptyList()
                
                trySend(movies)
            }
        
        awaitClose { listener.remove() }
    }

    // Single Fetch (যদি দরকার হয় ডিটেইলস এর জন্য)
    suspend fun getMoviesFromFirestore(): List<Movie> {
        return try {
            val snapshot = db.collection("movies").get().await()
            snapshot.documents.mapNotNull { doc ->
                doc.toObject(Movie::class.java)?.copy(id = doc.id)
            }
        } catch (e: Exception) {
            emptyList()
        }
    }
}
