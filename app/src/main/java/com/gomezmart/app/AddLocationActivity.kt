package com.gomezmart.app

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
import androidx.lifecycle.lifecycleScope
import com.google.android.gms.location.*
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FirebaseFirestore
import com.gomezmart.app.data.UserLocation
import com.gomezmart.app.databinding.ActivityAddLocationBinding
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext
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
        try {
            val osmConfig = Configuration.getInstance()
            osmConfig.userAgentValue = packageName
            val basePath = File(cacheDir, "osmdroid")
            if (!basePath.exists()) basePath.mkdirs()
            osmConfig.osmdroidBasePath = basePath
            val tilePath = File(basePath, "tiles")
            if (!tilePath.exists()) tilePath.mkdirs()
            osmConfig.osmdroidTileCache = tilePath
        } catch (e: Exception) {
            Log.e("AddLocation", "Error OSM config: ${e.message}")
        }
        
        binding = ActivityAddLocationBinding.inflate(layoutInflater)
        setContentView(binding.root)

        // Manejar insets para que los botones superiores e inferiores no choquen con las barras del sistema
        applyWindowInsetsMargins(binding.btnBackAddLocation, applyTop = true, applyBottom = false)
        applyWindowInsetsMargins(binding.btnToggleMapType, applyTop = true, applyBottom = false)
        applyWindowInsetsMargins(binding.cardLocationInputs, applyTop = false, applyBottom = true)

        fusedLocationClient = LocationServices.getFusedLocationProviderClient(this)

        setupMap()
        setupListeners()
        centerOnMyLocationFresh()
    }

    private fun hasLocationPermission(): Boolean {
        return ActivityCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED ||
               ActivityCompat.checkSelfPermission(this, Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED
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
        myLocationOverlay?.setDrawAccuracyEnabled(true) // Círculo azul de precisión
        binding.map.overlays.add(myLocationOverlay)

        if (hasLocationPermission()) {
            try {
                myLocationOverlay?.enableMyLocation()
            } catch (e: Exception) {
                Log.e("AddLocation", "Error enableMyLocation: ${e.message}")
            }
        }

        // Centrar automáticamente la primera vez que se obtenga ubicación
        myLocationOverlay?.runOnFirstFix {
            runOnUiThread {
                if (!isFinishing && !isDestroyed) {
                    myLocationOverlay?.myLocation?.let {
                        binding.map.controller.animateTo(it)
                        binding.map.controller.setZoom(17.5)
                    }
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
        if (!hasLocationPermission()) {
            ActivityCompat.requestPermissions(
                this,
                arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION),
                101
            )
            return
        }

        try {
            myLocationOverlay?.enableMyLocation()
        } catch (e: Exception) {
            Log.e("AddLocation", "Error enableMyLocation: ${e.message}")
        }

        val locationRequest = CurrentLocationRequest.Builder()
            .setPriority(Priority.PRIORITY_HIGH_ACCURACY)
            .setDurationMillis(5000)
            .build()

        try {
            fusedLocationClient.getCurrentLocation(locationRequest, null).addOnSuccessListener { location: Location? ->
                if (isFinishing || isDestroyed) return@addOnSuccessListener
                if (location != null) {
                    val current = GeoPoint(location.latitude, location.longitude)
                    binding.map.controller.animateTo(current)
                    binding.map.controller.setZoom(18.0)
                    reverseGeocode(current)
                } else {
                    try {
                        fusedLocationClient.lastLocation.addOnSuccessListener { lastLoc ->
                            if (isFinishing || isDestroyed) return@addOnSuccessListener
                            if (lastLoc != null) {
                                val point = GeoPoint(lastLoc.latitude, lastLoc.longitude)
                                binding.map.controller.animateTo(point)
                                binding.map.controller.setZoom(18.0)
                                reverseGeocode(point)
                            } else {
                                val overlayLoc = myLocationOverlay?.myLocation
                                if (overlayLoc != null) {
                                    binding.map.controller.animateTo(overlayLoc)
                                    binding.map.controller.setZoom(18.0)
                                    reverseGeocode(overlayLoc)
                                }
                            }
                        }
                    } catch (e: Exception) {
                        Log.e("AddLocation", "Error lastLocation: ${e.message}")
                    }
                }
            }.addOnFailureListener { e ->
                Log.e("AddLocation", "Error getCurrentLocation: ${e.message}")
            }
        } catch (e: SecurityException) {
            Log.e("AddLocation", "SecurityException getCurrentLocation: ${e.message}")
        } catch (e: Exception) {
            Log.e("AddLocation", "Exception getCurrentLocation: ${e.message}")
        }
    }

    private fun scheduleGeocoding() {
        geocodeRunnable?.let { geocodeHandler.removeCallbacks(it) }
        geocodeRunnable = Runnable {
            val center = binding.map.mapCenter
            if (center != null) {
                reverseGeocode(GeoPoint(center.latitude, center.longitude))
            }
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
                        if (!isFinishing && !isDestroyed) {
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
        val userId = auth.currentUser?.uid
        if (userId.isNullOrEmpty()) {
            Toast.makeText(this, "Usuario no autenticado", Toast.LENGTH_SHORT).show()
            return
        }
        val title = binding.etLocationTitle.text.toString().trim()
        val address = binding.etLocationAddress.text.toString().trim()
        val reference = binding.etLocationReference.text.toString().trim()
        val center = binding.map.mapCenter
        if (center == null) {
            Toast.makeText(this, "No se pudo obtener la posición del mapa", Toast.LENGTH_SHORT).show()
            return
        }
        val lat = center.latitude
        val lng = center.longitude

        if (title.isEmpty() || address.isEmpty()) {
            Toast.makeText(this, "Completa el título y la dirección", Toast.LENGTH_SHORT).show()
            return
        }

        lifecycleScope.launch {
            try {
                // Verificar si es la primera ubicación del usuario para marcarla como principal
                val existingLocs = db.collection("users").document(userId).collection("locations").get().await()
                val isFirst = existingLocs.isEmpty

                var city = ""
                var postalCode = ""
                try {
                    withContext(Dispatchers.IO) {
                        val geocoder = Geocoder(this@AddLocationActivity, Locale.getDefault())
                        val addresses = geocoder.getFromLocation(lat, lng, 1)
                        if (!addresses.isNullOrEmpty()) {
                            val addr = addresses[0]
                            city = addr.locality ?: addr.subAdminArea ?: ""
                            postalCode = addr.postalCode ?: ""
                        }
                    }
                } catch (e: Exception) {
                    Log.e("AddLocation", "Error geocoder save: ${e.message}")
                }

                val locationId = UUID.randomUUID().toString()
                val userLocation = UserLocation(
                    id = locationId,
                    title = title,
                    address = address,
                    reference = reference,
                    latitude = lat,
                    longitude = lng,
                    isDefault = isFirst
                )

                db.collection("users").document(userId).collection("locations").document(locationId)
                    .set(userLocation).await()

                if (isFirst) {
                    val prefs = getSharedPreferences("BuyerPrefs", MODE_PRIVATE)
                    prefs.edit()
                        .putString("buyer_city", city)
                        .putString("buyer_postal_code", postalCode)
                        .putString("buyer_address", address)
                        .apply()
                }

                Toast.makeText(this@AddLocationActivity, "Ubicación guardada con éxito ✨", Toast.LENGTH_SHORT).show()
                finish()
            } catch (e: Exception) {
                Toast.makeText(this@AddLocationActivity, "Error al guardar en la nube", Toast.LENGTH_SHORT).show()
            }
        }
    }

    override fun onResume() {
        super.onResume()
        binding.map.onResume()
        if (hasLocationPermission()) {
            try {
                myLocationOverlay?.enableMyLocation()
            } catch (e: Exception) {
                Log.e("AddLocation", "Error onResume enableMyLocation: ${e.message}")
            }
        }
    }

    override fun onPause() {
        super.onPause()
        binding.map.onPause()
        try {
            myLocationOverlay?.disableMyLocation()
        } catch (e: Exception) {
            Log.e("AddLocation", "Error onPause disableMyLocation: ${e.message}")
        }
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == 101 && grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
            try {
                myLocationOverlay?.enableMyLocation()
            } catch (e: Exception) {
                Log.e("AddLocation", "Error onRequestPermissionsResult enableMyLocation: ${e.message}")
            }
            centerOnMyLocationFresh()
        }
    }
}
