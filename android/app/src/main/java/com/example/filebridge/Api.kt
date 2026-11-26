package com.example.filebridge

import okhttp3.MultipartBody
import okhttp3.RequestBody
import okhttp3.ResponseBody
import retrofit2.Response
import retrofit2.http.Body
import retrofit2.http.DELETE
import retrofit2.http.GET
import retrofit2.http.Header
import retrofit2.http.Multipart
import retrofit2.http.PATCH
import retrofit2.http.POST
import retrofit2.http.PUT
import retrofit2.http.Part
import retrofit2.http.Path
import retrofit2.http.Query
import retrofit2.http.Streaming

data class FileItem(
    val id: String = "",
    val name: String = "",
    val parent: String = "",
    val rel: String = "",
    val type: String = "file",
    val size: Long = 0,
    val sha256: String = "",
    val mime: String = "",
    val updated_at: Double = 0.0,
)

data class ListResp(
    val parent: String = "",
    val dirs: List<FileItem> = emptyList(),
    val files: List<FileItem> = emptyList(),
)

data class Health(val ok: Boolean = false, val root: String = "", val files: Int = 0)
data class OpenResp(val url: String = "", val expires_in: Int = 0)
data class OkResp(val ok: Boolean = false, val purged: Boolean = false)
data class PatchBody(val name: String? = null, val parent: String? = null)
data class DirBody(val name: String, val parent: String = "")
data class TrashItem(
    val id: String = "",
    val name: String = "",
    val parent: String = "",
    val rel: String = "",
    val type: String = "file",
    val deleted_at: Double? = null,
)

interface FileApi {
    @GET("health")
    suspend fun health(): Health

    @GET("files")
    suspend fun list(
        @Query("parent") parent: String,
        @Header("x-api-key") key: String,
    ): ListResp

    @GET("files/{id}/open")
    suspend fun openUrl(
        @Path("id") id: String,
        @Header("x-api-key") key: String,
    ): OpenResp

    @Streaming
    @GET("files/{id}/content")
    suspend fun download(
        @Path("id") id: String,
        @Header("x-api-key") key: String,
    ): Response<ResponseBody>

    @Multipart
    @POST("files")
    suspend fun upload(
        @Part file: MultipartBody.Part,
        @Part("parent") parent: RequestBody,
        @Header("x-api-key") key: String,
    ): FileItem

    @Multipart
    @PUT("files/{id}")
    suspend fun update(
        @Path("id") id: String,
        @Part file: MultipartBody.Part,
        @Header("If-Match") sha256: String,
        @Header("x-api-key") key: String,
    ): FileItem

    @PATCH("files/{id}")
    suspend fun patch(
        @Path("id") id: String,
        @Body body: PatchBody,
        @Header("x-api-key") key: String,
    ): FileItem

    @POST("dirs")
    suspend fun mkdir(
        @Body body: DirBody,
        @Header("x-api-key") key: String,
    ): FileItem

    @DELETE("files/{id}")
    suspend fun delete(
        @Path("id") id: String,
        @Query("purge") purge: Int,
        @Header("x-api-key") key: String,
    ): OkResp

    @GET("trash")
    suspend fun trash(@Header("x-api-key") key: String): List<TrashItem>

    @POST("trash/{id}/restore")
    suspend fun restore(
        @Path("id") id: String,
        @Header("x-api-key") key: String,
    ): FileItem

    @DELETE("trash/{id}")
    suspend fun purgeTrash(
        @Path("id") id: String,
        @Header("x-api-key") key: String,
    ): OkResp
}
