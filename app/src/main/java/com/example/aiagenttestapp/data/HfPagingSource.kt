package com.example.aiagenttestapp.data

import androidx.paging.PagingSource
import androidx.paging.PagingState
import com.example.aiagent.engine.core.ModelFormat

/**
 * Pages HuggingFace search results. The first load fetches [HuggingFaceClient.searchFirstUrl]; every
 * later page follows the `Link: ...; rel="next"` cursor the previous response returned, so the whole
 * matching set is reachable by scrolling rather than the fixed first-30 the old one-shot search had.
 *
 * The key is the URL of a page (null = the first). Cursor pagination has no stable numeric anchor, so
 * [getRefreshKey] returns null and a refresh simply restarts from the first page.
 */
class HfPagingSource(
    private val client: HuggingFaceClient,
    private val query: String,
    private val format: ModelFormat,
) : PagingSource<String, HfRepo>() {

    override suspend fun load(params: LoadParams<String>): LoadResult<String, HfRepo> = try {
        val url = params.key ?: client.searchFirstUrl(query, format, params.loadSize)
        val page = client.fetchRepoPage(url)
        LoadResult.Page(data = page.repos, prevKey = null, nextKey = page.nextUrl)
    } catch (e: kotlinx.coroutines.CancellationException) {
        throw e
    } catch (e: Exception) {
        LoadResult.Error(e)
    }

    override fun getRefreshKey(state: PagingState<String, HfRepo>): String? = null
}
