package com.aeoncorex.streamx.data

import com.google.firebase.Firebase
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.firestore

/**
 * Singleton that provides the correct Firestore database instance.
 *
 * WHY THIS EXISTS:
 * The Firebase project uses a NAMED database called "default" (without parentheses).
 * The standard Firebase.firestore / FirebaseFirestore.getInstance() connects to
 * the standard "(default)" database (with parentheses) — which is a DIFFERENT,
 * empty database. This caused all client-side reads to return no data even though
 * the backend (Admin SDK with FIRESTORE_DATABASE_ID=default) writes correctly.
 *
 * USE:
 *   FirestoreDb.instance.collection("users").document(uid).get()
 *   FirestoreDb.instance.collection("users").document(uid).addSnapshotListener { ... }
 */
object FirestoreDb {
    val instance: FirebaseFirestore by lazy {
        Firebase.firestore("default")
    }
}
