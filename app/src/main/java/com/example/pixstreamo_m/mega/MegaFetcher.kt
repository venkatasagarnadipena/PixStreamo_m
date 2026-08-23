package com.example.pixstreamo_m.mega

import coil.ImageLoader
import coil.decode.DataSource
import coil.decode.ImageSource
import coil.fetch.FetchResult
import coil.fetch.Fetcher
import coil.fetch.SourceResult
import coil.request.Options
import okio.Buffer

class MegaFetcher(
    private val data: MegaImageNode,
    private val options: Options,
    private val megaRepository: MegaRepository,
    private val folderUrl: String,
    private val isThumbnail: Boolean
) : Fetcher {

    override suspend fun fetch(): FetchResult? {
        // Assign priority based on use-case
        val priority = if (isThumbnail) DecryptPriority.LOW else DecryptPriority.URGENT
        
        val imageBytes = megaRepository.decryptImage(data.handle, data.key, folderUrl, isThumbnail, priority)
        if (imageBytes.isEmpty()) return null
        
        val buffer = Buffer().write(imageBytes)

        return SourceResult(
            source = ImageSource(
                source = buffer,
                context = options.context
            ),
            mimeType = if (isThumbnail) "image/webp" else null,
            dataSource = DataSource.NETWORK
        )
    }

    class Factory(
        private val megaRepository: MegaRepository,
        private val folderUrl: String,
        private val isThumbnail: Boolean
    ) : Fetcher.Factory<MegaImageNode> {
        override fun create(data: MegaImageNode, options: Options, imageLoader: ImageLoader): Fetcher {
            return MegaFetcher(data, options, megaRepository, folderUrl, isThumbnail)
        }
    }
}
