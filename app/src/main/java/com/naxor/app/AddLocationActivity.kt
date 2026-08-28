package com.naxor.app

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.location.Geocoder
import android.location.Location
import android.location.LocationManager
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.View
import android.widget.Toast
import androidx.core.app.ActivityCompat
import com.google.android.gms.location.*
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FirebaseFirestore
import com.naxor.app.data.UserLocation
import com.naxor.app.databinding.ActivityAddLocationBinding
import org.osmdroid.config.Configuration
import org.osmdroid.events.MapListener
import org.osmdroid.events.ScrollEvent
import org.osmdroid.events.ZoomEvent
import org.osmdroid.tileprovider.tilesource.TileSourceFactory
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.CustomZoomButtonsController
import org.osmdroid.views.overlay.mylocation.GpsMyLocationProvider
import org.osmdroid.views.overlay.mylocation.MyLocationNewOverlay
import java.io.File
import java.util.*

class AddLocationActivity : BaseActivity() {

    private lateinit var binding: ActivityAddLocationBinding
    private val db = FirebaseFirestore.getInstance()
    private val auth = FirebaseAuth.getInstance()
    private var myLocationOverlay: MyLocationNewOverlay? = null
    private lateinit var fusedLocationClient: FusedLocationProviderClient
    
    private val geocodeHandler = Handler(Looper.getMainLooper())
    private var geocodeRunnable: Runnable? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Inicialización vital para OpenStreetMap
        Configuration.getInstance().userAgentValue = packageName
        
        binding = ActivityAddLocationBinding.inflate(layoutInflater)
        setContentView(binding.root)

        fusedLocationClient = LocationServices.getFusedLocationProviderClient(this)

        setupMap()
        setupListeners()
    }

    private fun setupMap() {
        binding.map.apply {
            setTileSource(TileSourceFactory.MAPNIK)
            setMultiTouchControls(true)
            zoomController.setVisibility(CustomZoomButtonsController.Visibility.NEVER)
            // Mejorar rendimiento visual
            isTilesScaledToDpi = true
        }
        
        val mapController = binding.map.controller
        mapController.setZoom(16.0)
        
        // Punto inicial por defecto (Perú)
        val peru = GeoPoint(-12.046374, -77.042793)
        mapController.setCenter(peru)

        // Configurar capa de ubicación real con mayor precisión
        val provider = GpsMyLocationProvider(this)
        provider.addLocationSource(LocationManager.GPS_PROVIDER)
        provider.addLocationSource(LocationManager.NETWORK_PROVIDER)
        
        myLocationOverlay = MyLocationNewOverlay(provider, binding.map)
        myLocationOverlay?.enableMyLocation()
        myLocationOverlay?.setDrawAccuracyEnabled(true) // Círculo azul de precisión
        binding.map.overlays.add(myLocationOverlay)

        // Centrar automáticamente la primera vez que se obtenga ubicación
        myLocationOverlay?.runOnFirstFix {
            runOnUiThread {
                myLocationOverlay?.myLocation?.let {
                    binding.map.controller.animateTo(it)
                    binding.map.controller.setZoom(17.5)
                }
            }
        }

        // Listener optimizado para evitar bloqueos del sistema (ANR)
        binding.map.addMapListener(object : MapListener {
            override fun onScroll(event: ScrollEvent?): Boolean {
                scheduleGeocoding()
                return true
            }
            override fun onZoom(event: ZoomEvent?): Boolean {
                scheduleGeocoding()
                return true
            }
        })
    }

    private fun setupListeners() {
        binding.btnBackAddLocation.setOnClickListener { finish() }
        
        binding.btnToggleMapType.setOnClickListener {
            if (binding.map.tileProvider.tileSource == TileSourceFactory.MAPNIK) {
                binding.map.setTileSource(TileSourceFactory.USGS_SAT)
                Toast.makeText(this, "Vista Satelital 🛰️", Toast.LENGTH_SHORT).show()
            } else {
                binding.map.setTileSource(TileSourceFactory.MAPNIK)
                Toast.makeText(this, "Vista Normal 🗺️", Toast.LENGTH_SHORT).show()
            }
        }

        binding.btnMyLocation.setOnClickListener {
            centerOnMyLocationFresh()
        }

        binding.btnSaveLocation.setOnClickListener {
            saveLocation()
        }
    }

    private fun centerOnMyLocationFresh() {
        if (ActivityCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION), 101)
            return
        }

        // Habilitar overlay por si acaso estaba apagado
        myLocationOverlay?.enableMyLocation()

        Toast.makeText(this, "Buscando tu posición... 🛰️", Toast.LENGTH_SHORT).show()

        // 1. Intentar con FusedLocation (Google Services) que es más rápido
        fusedLocationClient.lastLocation.addOnSuccessListener { lastLoc ->
            if (lastLoc != null) {
                val point = GeoPoint(lastLoc.latitude, lastLoc.longitude)
                binding.map.controller.animateTo(point)
                binding.map.controller.setZoom(18.0)
            }
        }

        // 2. Intentar obtener ubicación fresca de alta precisión
        val locationRequest = CurrentLocationRequest.Builder()
            .setPriority(Priority.PRIORITY_HIGH_ACCURACY)
            .build()

        fusedLocationClient.getCurrentLocation(locationRequest, null).addOnSuccessListener { location: Location? ->
            if (location != null) {
                val current = GeoPoint(location.latitude, location.longitude)
                binding.map.controller.animateTo(current)
                binding.map.controller.setZoom(18.0)
            } else {
                // 3. Fallback final al overlay de OSM
                val overlayLoc = myLocationOverlay?.myLocation
                if (overlayLoc != null) {
                    binding.map.controller.animateTo(overlayLoc)
                    binding.map.controller.setZoom(18.0)
                } else {
                    Toast.makeText(this, "Asegúrate de estar en un lugar abierto para recibir señal GPS 📍", Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    private fun scheduleGeocoding() {
        geocodeRunnable?.let { geocodeHandler.removeCallbacks(it) }
        geocodeRunnable = Runnable {
            val center = binding.map.mapCenter as? GeoPoint
            center?.let { reverseGeocode(it) }
        }
        // Esperar 1.2 segundos de calma antes de buscar la dirección para no congelar el app
        geocodeHandler.postDelayed(geocodeRunnable!!, 1200)
    }

    private fun reverseGeocode(geoPoint: GeoPoint) {
        // Ejecución estrictamente fuera del hilo principal
        Thread {
            try {
                val geocoder = Geocoder(this, Locale.getDefault())
                val addresses = geocoder.getFromLocation(geoPoint.latitude, geoPoint.longitude, 1)
                if (!addresses.isNullOrEmpty()) {
                    val addressLine = addresses[0].getAddressLine(0)
                    runOnUiThread {
                        if (!isFinishing) {
                            binding.etLocationAddress.setText(addressLine)
                        }
                    }
                }
            } catch (e: Exception) {
                Log.e("AddLocation", "Error Geocoder: ${e.message}")
            }
        }.start()
    }

    private fun saveLocation() {
        val userId = auth.currentUser?.uid ?: return
        val title = binding.etLocationTitle.text.toString().trim()
        val address = binding.etLocationAddress.text.toString().trim()
        val reference = binding.etLocationReference.text.toString().trim()
        val center = binding.map.mapCenter as GeoPoint

        if (title.isEmpty() || address.isEmpty()) {
            Toast.makeText(this, "Completa el título y la dirección", Toast.LENGTH_SHORT).show()
            return
        }

        val locationId = UUID.randomUUID().toString()
        val userLocation = UserLocation(
            id = locationId,
            title = title,
            address = address,
            reference = reference,
            latitude = center.latitude,
            longitude = center.longitude
        )

        db.collection("users").document(userId).collection("locations").document(locationId)
            .set(userLocation)
            .addOnSuccessListener {
                Toast.makeText(this, "Ubicación guardada con éxito ✨", Toast.LENGTH_SHORT).show()
                finish()
            }
            .addOnFailureListener {
                Toast.makeText(this, "Error al guardar en la nube", Toast.LENGTH_SHORT).show()
            }
    }

    override fun onResume() {
        super.onResume()
        binding.map.onResume()
        myLocationOverlay?.enableMyLocation()
    }

    override fun onPause() {
        super.onPause()
        binding.map.onPause()
        myLocationOverlay?.disableMyLocation()
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == 101 && grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
            centerOnMyLocationFresh()
        }
    }
}
