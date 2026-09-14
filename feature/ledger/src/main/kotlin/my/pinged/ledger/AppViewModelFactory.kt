package my.pinged.ledger

import android.app.Application
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewmodel.CreationExtras

/**
 * The smallest thing that lets `viewModel()` build one of this feature's
 * holders, written once for both of them.
 *
 * Written out rather than reached for through a DI framework, for the reason
 * `Databases`' KDoc gives about the container in `:feature:capture`'s `Graph`:
 * a framework here would be scaffolding with two consumers.
 *
 * **It holds the `Application` rather than reading `APPLICATION_KEY` out of
 * [CreationExtras].** The extras route depends on the owner propagating default
 * creation extras, which `ViewModelStoreNavEntryDecorator` does only when the
 * owner implements `HasDefaultViewModelProviderFactory` -- true in this app
 * only because the owner above the `NavEntry` is a `ComponentActivity`, and the
 * `error()` guarding that dependency is unreachable from any test. Given the
 * `Application` there is nothing to take from the extras, which is why [create]
 * uses neither of its parameters.
 *
 * In `my.pinged.ledger` rather than beside either holder, because neither of
 * the two packages that build one may depend on the other -- the same placement
 * rule, for the same reason, as `theme`'s `CANNOT_READ_YOUR_DATA`.
 */
class AppViewModelFactory(
    private val app: Application,
    private val holder: (Application) -> ViewModel,
) : ViewModelProvider.Factory {
    override fun <T : ViewModel> create(modelClass: Class<T>, extras: CreationExtras): T {
        @Suppress("UNCHECKED_CAST")
        return holder(app) as T
    }
}
