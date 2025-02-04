package ubb.victors3136

import android.app.Application
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Bundle
import android.util.Log
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.paddingFromBaseline
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBackIosNew
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.LibraryAdd
import androidx.compose.material.icons.filled.OpenInFull
import androidx.compose.material.icons.filled.Dataset
import androidx.compose.material.icons.filled.Web
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.livedata.observeAsState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.navigation.NavController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Delete
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Room
import androidx.room.RoomDatabase
import com.google.gson.Gson
import kotlinx.coroutines.CoroutineScope
import kotlin.random.Random
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response as OkHttpResponse
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import retrofit2.http.*
import retrofit2.Response as RetroResponse
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import java.text.SimpleDateFormat
import java.time.Instant
import java.util.Date
import java.util.Locale


object DateConverter {
    private val dateFormat = SimpleDateFormat("yyyy-MM-dd", Locale.US)

    fun stringToDate(dateString: String): Date? {
        return try {
            dateFormat.parse(dateString)
        } catch (e: Exception) {
            e.printStackTrace()
            null
        }
    }

    fun dateToString(date: Date): String {
        return dateFormat.format(date)
    }
}

fun Double.format(digits: Int) = "%.${digits}f".format(this)

@Entity(tableName = "items")
data class Item(
    @PrimaryKey(autoGenerate = false)
    val id: Int = INVALID_ID,
    val date: String = DateConverter.dateToString(Date.from(Instant.now())),
    val amount: Double = 0.0,
    val type: String = "",
    val category: String = "",
    val description: String = "",
) {
    companion object {
        const val INVALID_ID = -1
    }
}

interface ItemRepository {
    /**
     * Retrieve all the items from the the data source.
     */
    fun all(): Flow<List<Item>>

    /**
     * Retrieve an item from the given data source that matches with the [id].
     */
    fun get(id: Int): Flow<Item?>

    /**
     * Insert item in the data source
     */
    suspend fun insert(item: Item): Item?

    /**
     * Delete item from the data source
     */
    suspend fun delete(item: Item): Boolean

    suspend fun delete(id: Int): Boolean
}

@Dao
interface ItemDao {
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insert(item: Item)

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insert(items: List<Item>)

    @Delete
    suspend fun delete(item: Item): Int

    @Query("select * from items where id = :id")
    fun get(id: Int): Flow<Item>

    @Query("delete from items where id = :id")
    suspend fun delete(id: Int): Int

    @Query("select * from items")
    fun getAll(): Flow<List<Item>>

    @Query("delete from items")
    suspend fun clearAll(): Int
}

@Database(entities = [Item::class], version = 1, exportSchema = true)
abstract class ItemDatabase : RoomDatabase() {
    abstract fun itemDao(): ItemDao

    companion object {
        @Volatile
        private var INSTANCE: ItemDatabase? = null
        fun getDatabase(context: Context): ItemDatabase {
            INSTANCE =
                if (INSTANCE != null) INSTANCE
                else synchronized(this) {
                    Room.databaseBuilder(context, ItemDatabase::class.java, "app_database")
                        .fallbackToDestructiveMigration()
                        .build().also { INSTANCE = it }
                }
            return INSTANCE!!
        }

    }
}

class NetworkContext(private val context: Context) {
    fun isOnline(): Boolean {
        val connectivityManager =
            context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val network = connectivityManager.activeNetwork ?: return false
        val capabilities = connectivityManager.getNetworkCapabilities(network) ?: return false
        return capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) ||
                capabilities.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) ||
                capabilities.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)
    }
}

class NetworkStateReceiver(private val onConnected: () -> Unit) : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val isConnected = NetworkContext(context).isOnline()
        if (isConnected) {
            onConnected()
        }
    }
}

interface ItemApi {

    @POST("transaction/")
    suspend fun add(@Body item: Item): RetroResponse<Item?>

    @GET("transactions/")
    suspend fun get(): RetroResponse<List<Item>>

    @GET("transaction/{id}")
    suspend fun get(@Path("id") id: Int): RetroResponse<Item?>

    @GET("allTransactions/")
    suspend fun getAll(): RetroResponse<List<Item>>

    @DELETE("transaction/{id}")
    suspend fun delete(@Path("id") id: Int): RetroResponse<Unit>

}

object RetrofitClient {
    private const val BASE_URL = "http://localhost:2528/"

    val itemApi: ItemApi by lazy {
        Retrofit.Builder()
            .baseUrl(BASE_URL)
            .addConverterFactory(GsonConverterFactory.create())
            .build()
            .create(ItemApi::class.java)
    }
}

class LocalRepository(private val itemDao: ItemDao) : ItemRepository {
    constructor(context: Context) : this(ItemDatabase.getDatabase(context).itemDao())

    override fun all(): Flow<List<Item>> {
        Log.i("[Local repo]", "Get all")
        return itemDao.getAll()
    }

    override fun get(id: Int): Flow<Item?> {
        Log.i("[Local repo]", "Get $id")
        return itemDao.get(id)
    }

    override suspend fun insert(item: Item): Item {
        Log.i("[Local repo]", "Insert $item")
        itemDao.insert(item)
        return item
    }

    override suspend fun delete(item: Item): Boolean {
        Log.i("[Local repo]", "Delete $item")
        return itemDao.delete(item) > 0
    }

    override suspend fun delete(id: Int): Boolean {
        Log.i("[Local repo]", "Delete $id")
        return itemDao.delete(id) > 0
    }

    suspend fun clear() {
        Log.i("[Local repo]", "Clear")
        itemDao.clearAll()
    }

    suspend fun insert(items: List<Item>) {
        itemDao.insert(items)
    }
}

class RemoteRepository(private val context: Context, private val api: ItemApi) :
    ItemRepository {
    constructor(context: Context) : this(context, RetrofitClient.itemApi)

    override fun all(): Flow<List<Item>> = flow {
        Log.i("[Remote repo]", "Get all")
        val response = api.getAll()
        if (response.isSuccessful) {
            response.body()?.let { emit(it) } ?: emit(emptyList())
        } else {
            withContext(Dispatchers.Main) {
                Toast.makeText(
                    context,
                    "Couldn't retrieve the list of items.\nDetails: " + response.message(),
                    Toast.LENGTH_SHORT
                ).show()
            }
            Log.e("[Remote repo]", "Failed to fetch items: ${response.errorBody()?.string()}")
        }
    }

    override fun get(id: Int): Flow<Item?> = flow {
        Log.i("[Remote repo]", "Get $id")
        val response = api.get(id)
        if (response.isSuccessful) {
            emit(response.body())
        } else {
            withContext(Dispatchers.Main) {
                Toast.makeText(
                    context,
                    "Couldn't retrieve the requested item.\nDetails: " + response.message(),
                    Toast.LENGTH_SHORT
                ).show()
            }
            Log.e(
                "[Remote repo]",
                "Failed to fetch item with id $id: ${response.errorBody()?.string()}"
            )
        }
    }

    override suspend fun insert(item: Item): Item? {
        Log.i("[Remote repo]", "Insert $item")
        val response = api.add(item)
        if (!response.isSuccessful) {
            withContext(Dispatchers.Main) {
                Toast.makeText(
                    context, "Error when inserting the item\nDetails: " + response.message(),
                    Toast.LENGTH_SHORT
                ).show()
            }
            Log.e("[Remote repo]", "Failed to insert item: ${response.errorBody()?.string()}")
        }
        return response.body()
    }

    override suspend fun delete(item: Item): Boolean {
        Log.i("[Remote repo]", "Delete $item")
        return delete(item.id)
    }

    override suspend fun delete(id: Int): Boolean {
        Log.i("[Remote repo]", "Delete $id")
        val response = api.delete(id)
        if (!response.isSuccessful) {
            withContext(Dispatchers.Main) {
                Toast.makeText(
                    context, "Couldn't delete the requested item.\nDetails: " + response.message(),
                    Toast.LENGTH_SHORT
                ).show()
            }
            Log.e(
                "[Remote repo]",
                "Failed to delete item with id $id: ${response.errorBody()?.string()}"
            )
        }
        return response.isSuccessful
    }

}

object WebSocketClient {
    private const val WS_URL = "ws://localhost:2528/ws"

    private val client = OkHttpClient()
    private var webSocket: WebSocket? = null

    fun connect(listener: WebSocketListener) {
        Log.d("[WS Listener]", "Connecting to ws")
        val request = Request.Builder().url(WS_URL).build()
        webSocket = client.newWebSocket(request, listener)
    }

    fun disconnect() {
        Log.d("[WS Listener]", "Closing ws")
        webSocket?.close(1000, "Closing WebSocket")
        webSocket = null
    }
}

class SynchronizedRepository(context: Context) : ItemRepository {
    private val local: LocalRepository = LocalRepository(context)
    private val remote: RemoteRepository = RemoteRepository(context)
    private val network: NetworkContext = NetworkContext(context)

    init {
        WebSocketClient.connect(object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: OkHttpResponse) {
                Log.i("[WebSocket]", "Connected to server")
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                Log.i("[WebSocket]", "Received message: $text")

                val newItem = Gson().fromJson(text, Item::class.java)

                CoroutineScope(Dispatchers.IO).launch {
                    local.insert(newItem)
                    withContext(Dispatchers.Main) {
                        Toast.makeText(
                            context,
                            "New transaction received: ${newItem.type} - ${newItem.amount} RON",
                            Toast.LENGTH_LONG
                        ).show()
                    }
                }
//                (context as? MainActivity)?.viewModel?.refresh()
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: OkHttpResponse?) {
                Log.e("[WebSocket]", "WebSocket error: ${t.message}")
            }
        })
    }

    override fun all(): Flow<List<Item>> = flow {
        local.all().let { list ->
            if (network.isOnline() && list.firstOrNull().isNullOrEmpty()) {
                runCatching { remote.all().first() }
                    .onSuccess { remoteRecipes ->
                        local.insert(remoteRecipes)
                        emitAll(local.all())
                    }
                    .onFailure { emitAll(list) }
            } else {
                emitAll(list)
            }
        }
    }

    override fun get(id: Int): Flow<Item?> = flow {
        if (network.isOnline()) {
            runCatching { remote.get(id).first() }
                .onSuccess { it?.let { local.insert(it) } }
                .onFailure { emitAll(local.get(id)) }
        }
        emitAll(local.get(id))
    }

    override suspend fun insert(item: Item): Item? = runCatching {
        if (network.isOnline()) remote.insert(item)?.let { local.insert(it) }
        else null
    }.getOrDefault(null)

    override suspend fun delete(item: Item): Boolean = runCatching {
        network.isOnline() && remote.delete(item) && local.delete(item)
    }.getOrDefault(false)

    override suspend fun delete(id: Int): Boolean = runCatching {
        network.isOnline() && remote.delete(id) && local.delete(id)
    }.getOrDefault(false)
}

class ItemService(context: Context) : Application() {
    private val data: ItemRepository by lazy {
        Log.d("[Service]", "Create repo")
        SynchronizedRepository(context)
    }

    suspend fun add(item: Item?): Boolean {
        return if (item == null) {
            false
        } else try {
            data.insert(item)
            true
        } catch (e: Exception) {
            false
        }
    }


    suspend fun delete(item: Item?): Boolean {
        return if (item == null)
            false
        else try {
            data.delete(item.id)
            true
        } catch (e: Exception) {
            false
        }
    }

    suspend fun delete(itemId: Int): Boolean {
        return try {
            data.delete(itemId)
            true
        } catch (e: Exception) {
            false
        }
    }

    fun get(index: Int): Flow<Item?> {
        return data.get(index)
    }

    fun getAll(): Flow<List<Item>> {
        return data.all()
    }

}

@Composable
fun IconButton(
    onclick: () -> Unit,
    context: Context?,
    isEnabled: (Context?) -> Boolean,
    icon: ImageVector,
    description: String?,
) {
    Button(
        onClick = onclick, enabled = isEnabled(context)
    ) {
        Image(
            imageVector = icon,
            contentDescription = description ?: ""
        )
    }
}

@Composable
fun AddButton(
    onclick: () -> Unit,
    context: Context,
    isEnabled: (Context) -> Boolean,
) {
    Button(
        onClick = onclick,
        enabled = isEnabled(context)
    ) {
        Image(
            imageVector = Icons.Filled.LibraryAdd,
            contentDescription = "Add New Transaction"
        )
    }
}

@Composable
fun ReportsButton(
    onclick: () -> Unit,
    context: Context,
    isEnabled: (Context) -> Boolean,
) {
    Button(
        onClick = onclick,
        enabled = isEnabled(context)
    ) {
        Image(
            imageVector = Icons.Filled.Dataset,
            contentDescription = "View Reports"
        )
    }
}

@Composable
fun InsightsButton(
    onclick: () -> Unit,
    context: Context,
    isEnabled: (Context) -> Boolean,
) {
    Button(
        onClick = onclick,
        enabled = isEnabled(context)
    ) {
        Image(
            imageVector = Icons.Filled.Web,
            contentDescription = "View Insights"
        )
    }
}

@Composable
fun DeleteButton(
    action: () -> Unit,
    context: Context,
    isEnabled: (Context) -> Boolean,
) {
    IconButton(
        action,
        icon = Icons.Filled.Delete,
        description = "Delete",
        isEnabled = { it != null && isEnabled(it) },
        context = context
    )
}

@Composable
fun EditButton(
    action: () -> Unit,
    context: Context,
    isEnabled: (Context) -> Boolean,
) {
    IconButton(
        onclick = action,
        icon = Icons.Filled.Edit,
        description = "Edit",
        isEnabled = { it != null && isEnabled(it) },
        context = context,
    )
}

@Composable
fun ViewButton(
    action: () -> Unit,
    isEnabled: (Context) -> Boolean,
    context: Context
) {
    IconButton(
        onclick = action,
        icon = Icons.Filled.OpenInFull,
        description = "View",
        isEnabled = { it != null && isEnabled(it) },
        context = context,
    )
}

@Composable
fun BackButton(
    action: () -> Unit,
    isEnabled: (Context?) -> Boolean = { true },
    context: Context? = null
) = if (context != null) {
    IconButton(
        onclick = action,
        icon = Icons.Filled.ArrowBackIosNew,
        description = "Back",
        isEnabled = { isEnabled(it) },
        context = context
    )
} else {
    IconButton(
        onclick = action,
        icon = Icons.Filled.ArrowBackIosNew,
        description = "Back",
        isEnabled = { true },
        context = null
    )
}

@Composable
fun SubmitButton(
    action: () -> Unit,
    isEnabled: (Context) -> Boolean,
    context: Context
) {
    IconButton(
        onclick = action,
        icon = Icons.Filled.Check,
        description = "Submit",
        isEnabled = { it != null && isEnabled(it) },
        context = context,
    )
}

object Theme {
    val primaryBackground = Color(0xFF212121)
    val secondaryBackground = Color(0xFFCFD8DC)
    val primaryAccent = Color(0xFF00796b)
    val secondaryAccent = Color(0xFF3B7893)
    val primaryText = Color(0xFFCFD8DC)
    val secondaryText = Color(0xFF212121)
    val ultimateBackground = Color(0xBF364F57)
}

@Composable
fun DeleteConfirmationDialog(
    cleanup: () -> Unit,
    submit: () -> Unit,
    context: Context,
    isEnabled: (Context) -> Boolean,
) {
    AlertDialog(
        onDismissRequest = { cleanup() },
        title = {
            Text(
                text = "Confirm Deletion",
                color = Theme.secondaryText
            )
        },
        text = {
            Text(
                text = "Are you sure you want to delete this item? This action cannot be undone.",
                color = Theme.secondaryText
            )
        },
        confirmButton = {
            DeleteButton(
                { submit(); cleanup() },
                context = context,
                isEnabled = isEnabled
            )
        },
        dismissButton = {
            BackButton({ cleanup() })
        }
    )
}

@Composable
fun Header(title: String = "") {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(100.dp)
            .background(Theme.primaryBackground)
            .paddingFromBaseline(bottom = 24.dp),
        contentAlignment = Alignment.BottomCenter
    ) {
        Text(
            text = title,
            color = Theme.primaryText,
            style = MaterialTheme.typography.headlineLarge,
            textAlign = TextAlign.Center
        )
    }
}

@Composable
fun ErrorDisplay(message: String = "", confirmAction: () -> Boolean) {
    Column(
        Modifier
            .background(Theme.ultimateBackground)
            .fillMaxSize(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Text(
            color = Theme.primaryText, text = message,
            style = MaterialTheme.typography.titleLarge
        )
        BackButton({ confirmAction() })
    }
}

@Composable
fun ReadOneActivity(
    subjectId: Int,
    navigator: NavController,
    viewModel: ItemViewModel
) {
    val subjects by viewModel.items.observeAsState(emptyList())
    var deleteRequestId by remember { mutableStateOf<Int?>(null) }
    val subject = subjects.find { it.id == subjectId }

    if (subject == null) {
        return ErrorDisplay("Transaction is not part of the list") {
            navigator.popBackStack("read", inclusive = false)
        }
    }

    if (deleteRequestId != null) {
        return DeleteConfirmationDialog(
            submit = { viewModel.delete(deleteRequestId!!) },
            cleanup = { deleteRequestId = null; navigator.popBackStack("read", inclusive = false) },
            context = LocalContext.current.applicationContext,
            isEnabled = { NetworkContext(it).isOnline() }
        )
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Theme.ultimateBackground)
    ) {
        Header(subject.type)

        Column(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth(),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            Text(
                text = subject.category,
                color = Color.White,
                style = MaterialTheme.typography.headlineLarge,
                modifier = Modifier
                    .padding(bottom = 8.dp)
                    .align(Alignment.CenterHorizontally)
            )
            Text(
                text = "On ${subject.date}",
                color = Color.White,
                style = MaterialTheme.typography.bodyLarge,
                modifier = Modifier
                    .padding(bottom = 16.dp)
                    .align(Alignment.CenterHorizontally)
            )
            Text(
                text = "${subject.amount.format(2)} RON",
                color = Color.White,
                style = MaterialTheme.typography.bodyLarge,
                modifier = Modifier
                    .padding(bottom = 16.dp)
                    .align(Alignment.CenterHorizontally)
            )
        }

        Row(
            horizontalArrangement = Arrangement.SpaceEvenly,
            modifier = Modifier
                .fillMaxWidth()
                .padding(bottom = 64.dp)
        ) {
            BackButton({ navigator.popBackStack() })
            EditButton({ navigator.navigate("edit/${subjectId}") },
                context = LocalContext.current.applicationContext,
                isEnabled = {
                    NetworkContext(it).isOnline()
                })
            DeleteButton({ deleteRequestId = subjectId },
                context = LocalContext.current.applicationContext,
                isEnabled = { NetworkContext(it).isOnline() })
        }
    }
}

@Composable
fun SingleItemView(
    item: Item,
    onViewButtonClick: () -> Unit,
    onEditButtonClick: () -> Unit,
    onDeleteButtonClick: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(12.dp)
            .background(color = Theme.primaryBackground, shape = RoundedCornerShape(16.dp))
            .border(width = 2.dp, color = Theme.primaryAccent, shape = RoundedCornerShape(16.dp))
            .padding(12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {

        Spacer(modifier = Modifier.width(16.dp))
        Column(modifier = Modifier.weight(1f)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(
                    text = item.type,
                    style = MaterialTheme.typography.titleMedium,
                    color = Theme.primaryText,
                    modifier = Modifier.widthIn(max = 164.dp),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    text = "${item.amount.format(2)} RON",
                    style = MaterialTheme.typography.bodySmall,
                    color = Theme.primaryText,
                    modifier = Modifier.align(Alignment.CenterVertically)
                )

            }
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(
                    text = item.date,
                    style = MaterialTheme.typography.bodyMedium,
                    color = Theme.primaryText,
                    modifier = Modifier.align(Alignment.CenterVertically)
                )
                Text(
                    text = item.category,
                    style = MaterialTheme.typography.bodyLarge,
                    color = Theme.primaryText
                )
            }
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                ViewButton(
                    onViewButtonClick,
                    context = LocalContext.current.applicationContext,
                    isEnabled = { NetworkContext(it).isOnline() })
                EditButton(onEditButtonClick,
                    context = LocalContext.current.applicationContext,
                    isEnabled = { NetworkContext(it).isOnline() })
                DeleteButton(onDeleteButtonClick,
                    context = LocalContext.current.applicationContext,
                    isEnabled = { NetworkContext(it).isOnline() })
            }
        }
    }
}

@Composable
fun ReadAllActivity(
    navigator: NavController,
    viewModel: ItemViewModel
) {
    val itemList by viewModel.items.observeAsState(emptyList())
    var deleteRequestId by remember { mutableStateOf<Int?>(null) }
    if (deleteRequestId != null) {
        return DeleteConfirmationDialog(
            submit = { viewModel.delete(deleteRequestId!!) },
            cleanup = { deleteRequestId = null },
            isEnabled = { NetworkContext(it).isOnline() },
            context = LocalContext.current.applicationContext,
        )
    }
    return Scaffold(
        modifier = Modifier
            .fillMaxSize()
            .background(Theme.ultimateBackground)
    ) { innerPadding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Theme.ultimateBackground)
        ) {
            Column(modifier = Modifier.fillMaxSize()) {
                Header("My Transactions")
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f)
                        .padding(innerPadding)
                ) {
                    LazyColumn(
                        modifier = Modifier.fillMaxWidth(),
                        contentPadding = PaddingValues(vertical = 8.dp)
                    ) {
                        items(itemList) {
                            SingleItemView(
                                item = it,
                                onViewButtonClick = { navigator.navigate("read/${it.id}") },
                                onDeleteButtonClick = { deleteRequestId = it.id },
                                onEditButtonClick = { navigator.navigate("edit/${it.id}") }
                            )
                        }
                    }
                }
                Row(
                    horizontalArrangement = Arrangement.SpaceEvenly,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(bottom = 64.dp)
                ) {
                    AddButton(
                        { navigator.navigate("create") },
                        context = LocalContext.current.applicationContext,
                        isEnabled = { NetworkContext(it).isOnline() },
                    )
                    ReportsButton(
                        { navigator.navigate("reports") },
                        context = LocalContext.current.applicationContext,
                        isEnabled = { NetworkContext(it).isOnline() }
                    )
                    InsightsButton(
                        { navigator.navigate("insights") },
                        context = LocalContext.current.applicationContext,
                        isEnabled = { NetworkContext(it).isOnline() }
                    )

                }
            }
        }
    }

}

@Composable
fun CustomTextField(value: TextFieldValue, label: String, onValueChange: (TextFieldValue) -> Unit) {
    TextField(value = value, onValueChange = {
        onValueChange(it)
    }, label = { Text(text = label) }, singleLine = true, modifier = Modifier.padding(5.dp))
}

fun isStringFieldValid(value: String): Boolean = value.isNotEmpty()
fun isNumericFieldValid(value: String): Boolean =
    value.isNotEmpty() && (value.toDoubleOrNull() != null)

@Composable
fun ItemForm(
    default: Item,
    onSubmit: (subject: Item) -> Unit,
    onCancel: () -> Unit,
    modifier: Modifier = Modifier.Companion
) {
    val type = remember { mutableStateOf(TextFieldValue(default.type)) }
    val description = remember { mutableStateOf(TextFieldValue(default.description)) }
    val category = remember { mutableStateOf(TextFieldValue(default.category)) }
    val amount = remember { mutableStateOf(TextFieldValue(default.amount.toString())) }

    fun isValid(): Boolean {
        return isStringFieldValid(type.value.text) &&
                isStringFieldValid(description.value.text) &&
                isStringFieldValid(category.value.text) &&
                isNumericFieldValid(amount.value.text)
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Top,
    ) {
        CustomTextField(value = type.value, label = "type") {
            type.value = it
        }


        CustomTextField(value = category.value, label = "category") {
            category.value = it
        }

        CustomTextField(value = amount.value, label = "amount") {
            amount.value = it
        }

        CustomTextField(value = description.value, label = "description") {
            description.value = it
        }

        Spacer(modifier = Modifier.weight(1f))

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(bottom = 16.dp),
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            BackButton(onCancel)
            SubmitButton(
                {
                    val itemType = type.value.text
                    val itemDescription = description.value.text
                    val itemAmount = amount.value.text.toDouble()
                    val itemCategory = category.value.text
                    val itemId =
                        if (default.id == Item.INVALID_ID)
                            Random.Default.nextInt() else default.id
                    onSubmit(
                        Item(
                            type = itemType,
                            description = itemDescription,
                            amount = itemAmount,
                            category = itemCategory,
                            id = itemId
                        )
                    )
                },
                context = LocalContext.current.applicationContext,
                isEnabled = { context -> isValid() && NetworkContext(context).isOnline() },
            )
        }
    }
}

@Composable
fun CreateActivity(
    navigator: NavController,
    viewModel: ItemViewModel
) {
    Scaffold(
        modifier = Modifier
            .fillMaxSize()
            .background(Theme.ultimateBackground)
    ) { innerPadding ->
        Column(modifier = Modifier.background(Theme.ultimateBackground)) {
            Header("Add a new item")
            ItemForm(
                default = Item(),
                onSubmit = {
                    viewModel.add(it)
                    navigator.popBackStack("read", inclusive = false)
                }, onCancel = {
                    navigator.popBackStack("read", inclusive = false)
                },
                modifier = Modifier.padding(innerPadding)
            )
        }
    }
}

@Composable
fun ReportItem(month: String, total: Double) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(12.dp)
            .background(Theme.primaryBackground, shape = RoundedCornerShape(16.dp))
            .border(2.dp, Theme.primaryAccent, RoundedCornerShape(16.dp))
            .padding(12.dp),
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(text = month, color = Theme.primaryText, style = MaterialTheme.typography.titleMedium)
        Text(
            text = "${total.format(2)} RON",
            color = Theme.primaryText,
            style = MaterialTheme.typography.bodyMedium
        )
    }
}

@Composable
fun ReportsActivity(
    navigator: NavController,
    viewModel: ItemViewModel
) {
    val transactions by viewModel.items.observeAsState(null)

    Scaffold(
        modifier = Modifier
            .fillMaxSize()
            .background(Theme.ultimateBackground)
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .background(Theme.ultimateBackground)
        ) {
            Header("Monthly Spending Report")
            when {
                transactions == null -> {
                    Box(
                        modifier = Modifier.fillMaxSize(),
                        contentAlignment = Alignment.Center
                    ) {
                        CircularProgressIndicator(color = Theme.primaryAccent)
                    }
                }

                else -> {
                    val monthlySpending = transactions!!
                        .filter { it.type.lowercase() == "expense" }
                        .groupBy { it.date.substring(0, 7) }
                        .mapValues { entry -> entry.value.sumOf { it.amount } }
                        .toList()
                        .sortedByDescending { it.second }

                    when {
                        monthlySpending.isEmpty() -> Text(
                            text = "No transactions available.",
                            color = Theme.primaryText,
                            style = MaterialTheme.typography.bodyLarge
                        )

                        else ->
                            LazyColumn(
                                modifier = Modifier.fillMaxWidth(),
                                contentPadding = PaddingValues(16.dp)
                            ) {
                                items(monthlySpending) { (month, total) ->
                                    ReportItem(month, total)
                                }
                            }
                    }
                }
            }
            Spacer(modifier = Modifier.weight(1f))
            Row { BackButton({ navigator.popBackStack() }) }
        }
    }
}

@Composable
fun InsightsActivity(
    navigator: NavController,
    viewModel: ItemViewModel
) {
    val transactions by viewModel.items.observeAsState(null)

    Scaffold(
        modifier = Modifier
            .fillMaxSize()
            .background(Theme.ultimateBackground)
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .background(Theme.ultimateBackground)
        ) {
            Header("Top 3 Spending Categories")

            when {
                transactions == null -> {
                    Box(
                        modifier = Modifier.fillMaxSize(),
                        contentAlignment = Alignment.Center
                    ) {
                        CircularProgressIndicator(color = Theme.primaryAccent)
                    }
                }

                transactions!!.isEmpty() -> {
                    Box(
                        modifier = Modifier.fillMaxSize(),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = "No transactions available.",
                            color = Theme.primaryText,
                            style = MaterialTheme.typography.bodyLarge
                        )
                    }
                }

                else -> {
                    val topCategories = transactions!!
                        .filter { it.type.lowercase() == "expense" }
                        .groupBy { it.category }
                        .mapValues { entry -> entry.value.sumOf { it.amount } }
                        .toList()
                        .sortedByDescending { it.second }
                        .take(3)

                    LazyColumn(
                        modifier = Modifier.fillMaxWidth(),
                        contentPadding = PaddingValues(16.dp)
                    ) {
                        items(topCategories) { (category, total) ->
                            InsightItem(category, total)
                        }
                    }
                }
            }
            Spacer(modifier = Modifier.weight(1f))
            BackButton({ navigator.popBackStack() })
        }
    }
}

@Composable
fun InsightItem(category: String, total: Double) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(12.dp)
            .background(Theme.primaryBackground, shape = RoundedCornerShape(16.dp))
            .border(2.dp, Theme.primaryAccent, RoundedCornerShape(16.dp))
            .padding(12.dp),
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(
            text = category,
            color = Theme.primaryText,
            style = MaterialTheme.typography.titleMedium
        )
        Text(
            text = "${total.format(2)} RON",
            color = Theme.primaryText,
            style = MaterialTheme.typography.bodyMedium
        )
    }
}

class ItemViewModel(private val albumService: ItemService) : ViewModel() {
    private val _items = MutableLiveData<List<Item>>()
    val items: LiveData<List<Item>> get() = _items

    init {
        loadItems()
    }

    private fun loadItems() {
        viewModelScope.launch {
            albumService.getAll().collect { _items.postValue(it) }
        }
    }

    fun add(album: Item) {
        viewModelScope.launch {
            if (albumService.add(album)) {
                Log.d("ItemViewModel", "Item added: $album")
            } else {
                Log.e("ItemViewModel", "Error adding album")
            }
        }
    }

    fun delete(albumId: Int) {
        viewModelScope.launch {
            if (albumService.delete(albumId)) {
                Log.d("ItemViewModel", "Item deleted was number $albumId")
            } else {
                Log.e("ItemViewModel", "Error deleting album")
            }
        }
    }
}

@Composable
fun Main() {
    val viewModel =
        ItemViewModel(ItemService(LocalContext.current.applicationContext))
    val navController = rememberNavController()
    NavHost(navController = navController, startDestination = "read") {
        composable("read/{id}") {
            val id = it.arguments?.getString("id")
            ReadOneActivity(
                subjectId = id?.toIntOrNull() ?: Item.INVALID_ID,
                navigator = navController,
                viewModel = viewModel
            )
        }
        composable("edit/{id}") {
            val id = it.arguments?.getString("id")
            ReadOneActivity(
                subjectId = id?.toIntOrNull() ?: Item.INVALID_ID,
                navigator = navController,
                viewModel = viewModel
            )
        }
        composable("read") { ReadAllActivity(navController, viewModel) }
        composable("create") { CreateActivity(navController, viewModel) }
        composable("reports") { ReportsActivity(navController, viewModel) }
        composable("insights") { InsightsActivity(navController, viewModel) }
    }
}

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            Main()
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        WebSocketClient.disconnect()
    }
}
