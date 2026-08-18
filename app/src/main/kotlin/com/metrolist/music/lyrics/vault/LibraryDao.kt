package com.metrolist.music.lyrics.vault

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import kotlinx.coroutines.flow.Flow

@Dao
interface LibraryDao {
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertArtist(artist: ArtistEntity): Long

    @Query("SELECT * FROM artists WHERE name = :name LIMIT 1")
    suspend fun getArtistByName(name: String): ArtistEntity?

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertAlbum(album: AlbumEntity): Long

    @Query("SELECT * FROM albums WHERE name = :name AND artistId = :artistId LIMIT 1")
    suspend fun getAlbumByNameAndArtist(name: String, artistId: Int): AlbumEntity?

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertSong(song: SongEntity): Long

    @Query("SELECT * FROM songs WHERE title = :title AND albumId = :albumId LIMIT 1")
    suspend fun getSongByTitleAndAlbum(title: String, albumId: Int): SongEntity?

    @Query("UPDATE songs SET hasLyricsAvailable = :hasLyrics WHERE id = :songId")
    suspend fun updateSongLyricsAvailability(songId: Int, hasLyrics: Boolean)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertFile(file: FileEntity): Long

    @Query("DELETE FROM files WHERE songId = :songId")
    suspend fun deleteFilesForSong(songId: Int)

    @Query("SELECT * FROM files WHERE songId = :songId")
    fun getFilesForSong(songId: Int): Flow<List<FileEntity>>

    @Query("SELECT * FROM songs WHERE youtubeVideoId = :videoId LIMIT 1")
    suspend fun getSongByVideoId(videoId: String): SongEntity?

    @Query("""
        SELECT songs.* FROM songs 
        INNER JOIN albums ON songs.albumId = albums.id 
        INNER JOIN artists ON albums.artistId = artists.id 
        WHERE songs.title = :title AND artists.name = :artist LIMIT 1
    """)
    suspend fun checkSongExists(title: String, artist: String): SongEntity?

    @Query("""
        SELECT EXISTS(
            SELECT 1 FROM songs 
            INNER JOIN albums ON songs.albumId = albums.id 
            INNER JOIN artists ON albums.artistId = artists.id 
            WHERE songs.title = :title AND artists.name = :artist LIMIT 1
        )
    """)
    suspend fun checkSongExistsFast(title: String, artist: String): Boolean

    @Query("""
        UPDATE songs 
        SET lastCheckedAt = :timestamp, 
            hasLyricsAvailable = :hasLyrics,
            retry_count = :retryCount,
            all_formats_present = :allFormatsPresent
        WHERE id = :songId
    """)
    suspend fun updateSongCheckStatus(
        songId: Int,
        timestamp: Long,
        hasLyrics: Boolean,
        retryCount: Int = 0,
        allFormatsPresent: Boolean = false
    )

    @Query("""
        UPDATE songs 
        SET retry_count = :retryCount, 
            all_formats_present = :allFormatsPresent, 
            lastCheckedAt = :timestamp 
        WHERE id = :songId
    """)
    suspend fun updateSongRetryAndFormats(
        songId: Int,
        retryCount: Int,
        allFormatsPresent: Boolean,
        timestamp: Long = System.currentTimeMillis()
    )

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertOrUpdateSongIndex(songIndex: SongIndexEntity): Long

    @Query("SELECT * FROM song_index WHERE videoId = :videoId LIMIT 1")
    suspend fun getSongIndexByVideoId(videoId: String): SongIndexEntity?

    @Query("SELECT * FROM song_index WHERE videoId = :videoId LIMIT 1")
    fun observeSongIndexByVideoId(videoId: String): Flow<SongIndexEntity?>

    @Query("SELECT * FROM song_index WHERE title = :title AND artist = :artist LIMIT 1")
    suspend fun getSongIndexByTitleAndArtist(title: String, artist: String): SongIndexEntity?

    @Query("UPDATE song_index SET retry_count = :retryCount, all_formats_present = :allFormatsPresent WHERE videoId = :videoId")
    suspend fun updateSongIndexStatus(videoId: String, retryCount: Int, allFormatsPresent: Boolean)

    @Query("UPDATE song_index SET activeLyricFile = :activeLyricFile WHERE videoId = :videoId")
    suspend fun updateActiveLyricFile(videoId: String, activeLyricFile: String)


    @Transaction
    suspend fun insertFullSongData(artist: ArtistEntity, album: AlbumEntity, song: SongEntity, file: FileEntity) {
        val artistId = insertArtist(artist)
        val finalArtistId = if (artistId == -1L) getArtistByName(artist.name)?.id?.toLong() ?: return else artistId
        
        val albumToInsert = album.copy(artistId = finalArtistId.toInt())
        val albumId = insertAlbum(albumToInsert)
        val finalAlbumId = if (albumId == -1L) getAlbumByNameAndArtist(albumToInsert.name, finalArtistId.toInt())?.id?.toLong() ?: return else albumId
        
        val songToInsert = song.copy(albumId = finalAlbumId.toInt())
        val songId = insertSong(songToInsert)
        val finalSongId = if (songId == -1L) getSongByTitleAndAlbum(songToInsert.title, finalAlbumId.toInt())?.id?.toLong() ?: return else songId
        
        insertFile(file.copy(songId = finalSongId.toInt()))
    }
}

