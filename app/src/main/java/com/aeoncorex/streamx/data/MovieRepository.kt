package com.aeoncorex.streamx.data

import com.aeoncorex.streamx.model.Movie
import com.google.firebase.firestore.FirebaseFirestore
import kotlinx.coroutines.tasks.await

object MovieRepository {
    private val db = FirebaseFirestore.getInstance()

    // Firestore থেকে মুভি লিস্ট আনা
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
