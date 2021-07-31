package com.example.yugiohcarddict

import android.content.Context
import android.os.Bundle
import android.util.Log
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.material.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.PopupProperties
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import coil.compose.rememberImagePainter
import com.example.yugiohcarddict.ui.theme.YugiohCardDictTheme
import com.opencsv.CSVReader
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.io.BufferedReader
import java.io.InputStreamReader
import java.util.*
import kotlin.collections.HashMap

const val TAG = "MainActivity.kt"
const val ALPHABET_SIZE = 26
const val SPLIT_LIMITER_REGEX = "\\P{Alpha}+"

@Immutable
data class Doc(private var _tokenCount: Int, val id: String, val title: String, val url: String) {
    constructor(id: String, title: String, url: String) : this(0, id, title, url) {
    }

    val tokenCount: Int get() = _tokenCount
    fun tokenizeTitle(): Map<String, Int> {
        val lowerTitle = title.trim().lowercase(Locale.getDefault())
        var tokenStartIndex = 0
        val deduplicateMap = HashMap<String, Int>()
        _tokenCount = 0
        for (token in lowerTitle.split(SPLIT_LIMITER_REGEX.toRegex())) {
            if (token.isNotBlank()) {
                if (!deduplicateMap.containsKey(token)) {
                    deduplicateMap[token] = tokenStartIndex
                }
                tokenStartIndex += token.length
                _tokenCount++
            } else {
                tokenStartIndex++
            }
        }

        return deduplicateMap
    }
}

@Immutable
data class TokenPosition(val doc: Doc, val position: Int)

@Immutable
data class InvertedIndex(val token: String, val tokenPositions: MutableList<TokenPosition>) {
    constructor(token: String) : this(token, LinkedList<TokenPosition>()) {
    }

    fun addTokenPosition(tokenPosition: TokenPosition) {
        tokenPositions.add(tokenPosition)
    }
}

@Immutable
data class TokenTrieNode(val children: Array<TokenTrieNode?>, var invertedIndex: InvertedIndex?) {
    constructor() :
            this(arrayOfNulls<TokenTrieNode>(ALPHABET_SIZE), null) {
    }

    fun insert(invertedIndex: InvertedIndex) {
        var currentNode: TokenTrieNode = this
        for (i in invertedIndex.token.indices) {
            val childIndex = invertedIndex.token[i] - 'a'
            if (childIndex in 0 until ALPHABET_SIZE) {
                if (currentNode.children[childIndex] == null) {
                    currentNode.children[childIndex] = TokenTrieNode()
                }
                currentNode = currentNode.children[childIndex]!!
            } else {
                return
            }
        }
        currentNode.invertedIndex = invertedIndex
    }

    fun search(key: String): InvertedIndex? {
        if (key.isNotEmpty()) {
            var searchKey = key.lowercase(Locale.getDefault())
            var currentNode: TokenTrieNode = this
            for (i in searchKey.indices) {
                val childIndex = searchKey[i] - 'a'
                if ((childIndex in 0 until ALPHABET_SIZE) && currentNode.children[childIndex] != null) {
                    currentNode = currentNode.children[childIndex]!!
                } else {
                    return null
                }
            }
            return currentNode.invertedIndex
        }
        return null
    }
}

class MainScreenViewModel : ViewModel() {
    var selectedDoc: MutableState<Doc?> = mutableStateOf(null)
    var searchedItems = mutableStateListOf<Doc>()
    var idToDoc: MutableMap<String, Doc> = mutableMapOf()
    val tokenTrieNode: MutableState<TokenTrieNode> = mutableStateOf(TokenTrieNode())
    var searchJob: Job? = null

    fun startIndexDoc(context: Context) {
        val stream = InputStreamReader(
            context.assets.open("cards.csv")
        )
        val reader = BufferedReader(stream)
        val csvReader = CSVReader(reader)
        csvReader.readNext()
        var record = csvReader.readNext()
        while (record != null) {
            if (record.isNotEmpty() && record[0].trim().isNotEmpty() && record[1].trim()
                    .isNotEmpty()
            ) {
                val doc = Doc(
                    id = record[0],
                    title = record[1],
                    url = record[13]
                )
                val tokensToPosition = doc.tokenizeTitle()
                val trieRoot = tokenTrieNode.value
                for ((token, position) in tokensToPosition) {
                    var invertedIndex = trieRoot.search(token)
                    if (invertedIndex == null) {
                        invertedIndex = InvertedIndex(token)
                        trieRoot.insert(invertedIndex)
                    }
                    invertedIndex.addTokenPosition(TokenPosition(doc, position))
                }
                idToDoc[doc.id] = doc
            }
            record = csvReader.readNext()
        }
        csvReader.close()
    }

    private fun getSuggestionsInTrie(keywords: String, limit: Int): List<Doc> {
        if (keywords.isNotEmpty()) {
            val lowercaseKeywords = keywords.trim().lowercase(Locale.getDefault())
            val ranks = PriorityQueue<Pair<Doc, Int>>(compareBy { it.second })
            val frequencies = mutableMapOf<String, Int>().withDefault { 0 }
            for (token in lowercaseKeywords.split(SPLIT_LIMITER_REGEX.toRegex())
                .filter { it.isNotBlank() }) {
                var invertedIndex = tokenTrieNode.value.search(token)
                if (invertedIndex != null) {
                    for (tokenPosition in invertedIndex.tokenPositions) {
                        if (!frequencies.containsKey(tokenPosition.doc.id)) {
                            frequencies[tokenPosition.doc.id] = 1
                        } else {
                            frequencies[tokenPosition.doc.id] =
                                frequencies.getValue(tokenPosition.doc.id) + 1
                            val rank =
                                ranks.find { pair -> pair.first.id == tokenPosition.doc.id }
                            ranks.remove(rank)
                        }
                        val reversedTokenCount = 10 - (tokenPosition.doc.tokenCount % 10)
                        val reversedPosition = 100 - (tokenPosition.position % 100)
                        val calculatedRank =
                            frequencies.getValue(tokenPosition.doc.id) * 1000 + reversedTokenCount * 100 + reversedPosition
                        ranks.add(Pair(tokenPosition.doc, calculatedRank))

                        if (ranks.size > limit) {
                            ranks.remove()
                        }
                    }
                } else {
                    Log.d(TAG, "getSuggestionsInTrie: inverted index not found")
                }
            }
            val list = LinkedList<Doc>()
            while (!ranks.isEmpty()) {
                ranks.poll()?.let{
                    list.addFirst(it.first)
                }
            }
            return list
        }
        return emptyList();
    }

    fun debounceSearch(keyword: String) {
        searchJob?.cancel()
        searchedItems.clear()
        if (!keyword.isEmpty()) {
            searchJob = viewModelScope.launch {
                delay(500)
                val result = getSuggestionsInTrie(keyword, 30)
                searchedItems.addAll(result)
            }
        }
    }

    fun setSelectedDoc(doc: Doc) {
        selectedDoc.value = doc
        searchedItems.clear()
    }
}


class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val viewModel = MainScreenViewModel()
        viewModel.startIndexDoc(this)
        window.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_VISIBLE)
        setContent {
            YugiohCardDictTheme {
                Surface(color = MaterialTheme.colors.background) {
                    MainScreen(viewModel)
                }
            }
        }
    }
}

@Composable
fun MainScreen(viewModel: MainScreenViewModel) {
    Column(
        Modifier
            .padding(16.dp)
            .fillMaxWidth()
    ) {
        SearchCardDropdown(label = "Search",
            items = viewModel.searchedItems,
            onTextChange = { viewModel.debounceSearch(it) },
            onSelect = { viewModel.setSelectedDoc(it) },
            onDismiss = { viewModel.searchedItems.clear() })
        Divider()
        viewModel.selectedDoc.value?.let {
            Image(
                painter = rememberImagePainter(
                    data = it.url,
                    builder = {
                        crossfade(true)
                    }
                ),
                contentScale = ContentScale.FillWidth,
                contentDescription = it.title,
                modifier = Modifier.fillMaxWidth()
            )
        }

    }
}

@Composable
fun SearchCardDropdown(
    label: String,
    items: List<Doc>,
    onTextChange: (String) -> Unit,
    onSelect: (Doc) -> Unit,
    onDismiss: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .wrapContentSize(Alignment.TopStart)
    )
    {
        var searchText by rememberSaveable { mutableStateOf("") }

        val localFocusManager = LocalFocusManager.current
        OutlinedTextField(
            value = searchText,
            onValueChange = {
                searchText = it
                onTextChange(it)
            },
            modifier = Modifier
                .fillMaxWidth()
                .clickable(onClick = {
                    if (searchText.isNotEmpty()) {
                        onTextChange(searchText)
                    }
                })
                .padding(0.dp, 20.dp, 0.dp, 0.dp),
            label = { Text(label) }
        )
        DropdownMenu(
            expanded = items.isNotEmpty(),
            properties = PopupProperties(focusable = false),
            onDismissRequest = onDismiss,
            modifier = Modifier
                .fillMaxWidth()
                .requiredSizeIn(maxHeight = 200.dp)
                .background(
                    MaterialTheme.colors.background.copy(alpha = 0.5f)
                )
        ) {
            items.forEachIndexed { index, doc ->
                DropdownMenuItem(onClick = {
                    searchText = items[index].title
                    localFocusManager.clearFocus()
                    onSelect(doc)
                }) {
                    Text(text = doc.title)
                }
            }
        }
    }
}


@Preview(showBackground = true)
@Composable
fun DefaultPreview() {
    YugiohCardDictTheme {
        Column {
            SearchCardDropdown("Sample search",
                items = emptyList(),
                onTextChange = {},
                onSelect = {},
                onDismiss = {})
            Divider(thickness = 2.dp)
            Image(
                painter = rememberImagePainter(
                    data = "https://storage.googleapis.com/ygoprodeck.com/pics/34541863.jpg",
                    builder = {
                        crossfade(true)
                    }
                ),
                contentScale = ContentScale.FillWidth,
                contentDescription = "it.title",
                modifier = Modifier
                    .padding(0.dp, 16.dp, 0.dp, 0.dp)
                    .fillMaxWidth()
            )
        }
    }
}