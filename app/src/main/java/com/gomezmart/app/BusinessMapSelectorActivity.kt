package com.gomezmart.app

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.location.Geocoder
import android.location.Location
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.widget.Toast
import androidx.core.app.ActivityCompat
import com.google.android.gms.location.*
import com.gomezmart.app.databinding.ActivityBusinessMapSelectorBinding
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

class BusinessMapSelectorActivity : BaseActivity() {

    private lateinit var binding: ActivityBusinessMapSelectorBinding
    private var myLocationOverlay: MyLocationNewOverlay? = null
    private lateinit var fusedLocationClient: FusedLocationProviderClient
    
    private val geocodeHandler = Handler(Looper.getMainLooper())
    private var geocodeRunnable: Runnable? = null
    
    private var selectedLat = 0.0
    private var selectedLng = 0.0
    private var selectedAddress = ""

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Cargar configuración de osmdroid antes del layout
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
            Log.e("BusinessMapSelector", "Error OSM config: ${e.message}")
        }
        
        binding = ActivityBusinessMapSelectorBinding.inflate(layoutInflater)
        setContentView(binding.root)

        // Manejar insets para que los botones y tarjetas no choquen con las barras del sistema
        applyWindowInsetsMargins(binding.btnBackSelector, applyTop = true, applyBottom = false)
        applyWindowInsetsMargins(binding.btnSelectorMyLocation, applyTop = true, applyBottom = false)
        applyWindowInsetsMargins(binding.cardSelectorFooter, applyTop = false, applyBottom = true)

        fusedLocationClient = LocationServices.getFusedLocationProviderClient(this)

        setupMap()
        setupListeners()
    }

    private fun hasLocationPermission(): Boolean {
        return ActivityCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED ||
               ActivityCompat.checkSelfPermission(this, Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED
    }

    private fun setupMap() {
        binding.mapSelector.apply {
            setTileSource(TileSourceFactory.MAPNIK)
            setMultiTouchControls(true)
            zoomController.setVisibility(CustomZoomButtonsController.Visibility.NEVER)
            
            // Mejorar la fluidez del mapa
            isTilesScaledToDpi = true
            setFlingEnabled(true)
            minZoomLevel = 4.0
            maxZoomLevel = 20.0
            
            // Evitar que el mapa se sienta "trabado" al moverlo rápido
            setHasTransientState(true)
        }
        
        val mapController = binding.mapSelector.controller
        mapController.setZoom(17.5)
        
        myLocationOverlay = MyLocationNewOverlay(GpsMyLocationProvider(this), binding.mapSelector)
        if (hasLocationPermission()) {
            try {
                myLocationOverlay?.enableMyLocation()
            } catch (e: Exception) {
                Log.e("BusinessMapSelector", "Error enableMyLocation: ${e.message}")
            }
        }
        myLocationOverlay?.runOnFirstFix {
            runOnUiThread {
                if (!isFinishing && !isDestroyed) {
                    myLocationOverlay?.myLocation?.let {
                        binding.mapSelector.controller.animateTo(it)
                        scheduleGeocoding()
                    }
                }
            }
        }
        binding.mapSelector.overlays.add(myLocationOverlay)

        centerOnMyLocation()

        binding.mapSelector.addMapListener(object : MapListener {
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
        binding.btnBackSelector.setOnClickListener { finish() }
        
        binding.btnSelectorMyLocation.setOnClickListener {
            centerOnMyLocation()
        }

        binding.btnConfirmSelector.setOnClickListener {
            if (selectedLat == 0.0) {
                Toast.makeText(this, "Por favor selecciona una ubicación", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            val data = Intent()
            data.putExtra("LAT", selectedLat)
            data.putExtra("LNG", selectedLng)
            data.putExtra("ADDRESS", selectedAddress)
            setResult(RESULT_OK, data)
            finish()
        }
    }

    private fun centerOnMyLocation() {
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
            Log.e("BusinessMapSelector", "Error enableMyLocation: ${e.message}")
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
                    binding.mapSelector.controller.animateTo(current)
                    binding.mapSelector.controller.setZoom(18.0)
                    selectedLat = location.latitude
                    selectedLng = location.longitude
                    reverseGeocode(current)
                } else {
                    try {
                        fusedLocationClient.lastLocation.addOnSuccessListener { lastLoc ->
                            if (isFinishing || isDestroyed) return@addOnSuccessListener
                            if (lastLoc != null) {
                                val current = GeoPoint(lastLoc.latitude, lastLoc.longitude)
                                binding.mapSelector.controller.animateTo(current)
                                binding.mapSelector.controller.setZoom(18.0)
                                selectedLat = lastLoc.latitude
                                selectedLng = lastLoc.longitude
                                reverseGeocode(current)
                            } else {
                                val overlayLoc = myLocationOverlay?.myLocation
                                if (overlayLoc != null) {
                                    binding.mapSelector.controller.animateTo(overlayLoc)
                                    binding.mapSelector.controller.setZoom(18.0)
                                    selectedLat = overlayLoc.latitude
                                    selectedLng = overlayLoc.longitude
                                    reverseGeocode(overlayLoc)
                                }
                            }
                        }
                    } catch (e: Exception) {
                        Log.e("BusinessMapSelector", "Error lastLocation: ${e.message}")
                    }
                }
            }.addOnFailureListener { e ->
                Log.e("BusinessMapSelector", "Error getCurrentLocation: ${e.message}")
            }
        } catch (e: SecurityException) {
            Log.e("BusinessMapSelector", "SecurityException getCurrentLocation: ${e.message}")
        } catch (e: Exception) {
            Log.e("BusinessMapSelector", "Exception getCurrentLocation: ${e.message}")
        }
    }

    private fun scheduleGeocoding() {
        geocodeRunnable?.let { geocodeHandler.removeCallbacks(it) }
        geocodeRunnable = Runnable {
            val center = binding.mapSelector.mapCenter
            if (center != null) { 
                selectedLat = center.latitude
                selectedLng = center.longitude
                reverseGeocode(GeoPoint(center.latitude, center.longitude)) 
            }
        }
        // Reducir el tiempo de espera para que la dirección aparezca más rápido
        geocodeHandler.postDelayed(geocodeRunnable!!, 600)
    }

    private fun reverseGeocode(geoPoint: GeoPoint) {
        Thread {
            try {
                val geocoder = Geocoder(this, Locale.getDefault())
                val addresses = geocoder.getFromLocation(geoPoint.latitude, geoPoint.longitude, 1)
                if (!addresses.isNullOrEmpty()) {
                    val addressLine = addresses[0].getAddressLine(0)
                    selectedAddress = addressLine
                    runOnUiThread {
                        if (!isFinishing && !isDestroyed) {
                            binding.tvSelectedAddress.text = addressLine
                        }
                    }
                }
            } catch (e: Exception) { 
                runOnUiThread {
                    if (!isFinishing && !isDestroyed) {
                        binding.tvSelectedAddress.text = "Ubicación sin dirección exacta"
                    }
                }
            }
        }.start()
    }

    override fun onResume() {
        super.onResume()
        binding.mapSelector.onResume()
        if (hasLocationPermission()) {
            try {
                myLocationOverlay?.enableMyLocation()
            } catch (e: Exception) {
                Log.e("BusinessMapSelector", "Error onResume enableMyLocation: ${e.message}")
            }
        }
    }

    override fun onPause() {
        super.onPause()
        binding.mapSelector.onPause()
        try {
            myLocationOverlay?.disableMyLocation()
        } catch (e: Exception) {
            Log.e("BusinessMapSelector", "Error onPause disableMyLocation: ${e.message}")
        }
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == 101 && grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
            try {
                myLocationOverlay?.enableMyLocation()
            } catch (e: Exception) {}
            centerOnMyLocation()
        }
    }
}
