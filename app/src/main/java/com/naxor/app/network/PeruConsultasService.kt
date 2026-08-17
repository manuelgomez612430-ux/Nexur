package com.naxor.app.network

import com.google.gson.annotations.SerializedName
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import retrofit2.http.GET
import retrofit2.http.Path
import retrofit2.http.Query

// Modelos de Respuesta compatibles con apisperu.com
data class DniResponse(
    val success: Boolean,
    val data: DniData?
)

data class DniData(
    @SerializedName("numero") val numero: String?,
    @SerializedName("nombre") val nombre: String?,
    @SerializedName("nombres") val nombres: String?,
    @SerializedName("apellidoPaterno") val apellidoPaterno: String?,
    @SerializedName("apellidoMaterno") val apellidoMaterno: String?,
    @SerializedName("nombre_completo") val nombre_completo: String?,
    @SerializedName("nombreCompleto") val nombreCompleto: String?
)

data class RucResponse(
    val success: Boolean,
    val data: RucData?
)

data class RucData(
    @SerializedName("numero") val numero: String?,
    @SerializedName("razonSocial") val razonSocial: String?,
    @SerializedName("razon_social") val razon_social: String?,
    @SerializedName("nombre_o_razon_social") val nombre_o_razon_social: String?,
    @SerializedName("direccion") val direccion: String?,
    @SerializedName("direccion_completa") val direccion_completa: String?,
    @SerializedName("estado") val estado: String?,
    @SerializedName("condicion") val condicion: String?
)

// Interfaz API
interface PeruConsultasApi {
    @GET("dni/{dni}")
    suspend fun buscarDni(
        @Path("dni") dni: String,
        @Query("token") token: String
    ): DniResponse

    @GET("ruc/{ruc}")
    suspend fun buscarRuc(
        @Path("ruc") ruc: String,
        @Query("token") token: String
    ): RucResponse
}

// Cliente Retrofit
object RetrofitClient {
    private const val BASE_URL = "https://dniruc.apisperu.com/api/v1/"
    
    val api: PeruConsultasApi by lazy {
        Retrofit.Builder()
            .baseUrl(BASE_URL)
            .addConverterFactory(GsonConverterFactory.create())
            .build()
            .create(PeruConsultasApi::class.java)
    }
}
