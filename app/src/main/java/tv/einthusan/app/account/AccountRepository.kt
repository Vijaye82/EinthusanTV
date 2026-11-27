package tv.einthusan.app.account

/**
 * Authentication state exposed to the UI. Never store a raw password in preferences or logs.
 */
sealed interface AccountState {
    data object SignedOut : AccountState
    data object SigningIn : AccountState
    data class SignedIn(val displayName: String? = null, val expiresAtEpochMs: Long? = null) : AccountState
    data class Error(val message: String) : AccountState
}

data class SignInRequest(val usernameOrEmail: String, val password: CharArray)

/**
 * Adapter for Einthusan's explicitly approved sign-in/session API.
 * Implementations must use the approved flow, handle session expiry and clear secrets promptly.
 */
interface AccountRepository {
    suspend fun currentState(): AccountState
    suspend fun signIn(request: SignInRequest): AccountState
    suspend fun signOut()
}
