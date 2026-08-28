package com.naxor.app

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.location.Geocoder
import android.location.Location
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.widget.Toast
import androidx.core.app.ActivityCompat
import com.google.android.gms.location.*
import com.naxor.app.databinding.ActivityBusinessMapSelectorBinding
import org.osmdroid.config.Configuration
import org.osmdroid.events.MapListener
import org.osmdroid.events.ScrollEvent
import org.osmdroid.events.ZoomEvent
import org.osmdroid.tileprovider.tilesource.TileSourceFactory
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.CustomZoomButtonsController
import org.osmdroid.views.overlay.mylocation.GpsMyLocationProvider
import org.osmdroid.views.overlay.mylocation.MyLocationNewOverlay
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
        Configuration.getInstance().userAgentValue = packageName
        binding = ActivityBusinessMapSelectorBinding.inflate(layoutInflater)
        setContentView(binding.root)

        fusedLocationClient = LocationServices.getFusedLocationProviderClient(this)

        setupMap()
        setupListeners()
    }

    private fun setupMap() {
        binding.mapSelector.apply {
            setTileSource(TileSourceFactory.MAPNIK)
            setMultiTouchControls(true)
            zoomController.setVisibility(CustomZoomButtonsController.Visibility.NEVER)
            isTilesScaledToDpi = true
        }
        
        val mapController = binding.mapSelector.controller
        mapController.setZoom(16.0)
        
        val peru = GeoPoint(-12.046374, -77.042793)
        mapController.setCenter(peru)

        myLocationOverlay = MyLocationNewOverlay(GpsMyLocationProvider(this), binding.mapSelector)
        myLocationOverlay?.enableMyLocation()
        binding.mapSelector.overlays.add(myLocationOverlay)

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
            val data = Intent()
            data.putExtra("LAT", selectedLat)
            data.putExtra("LNG", selectedLng)
            data.putExtra("ADDRESS", selectedAddress)
            setResult(RESULT_OK, data)
            finish()
        }
    }

    private fun centerOnMyLocation() {
        if (ActivityCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.ACCESS_FINE_LOCATION), 101)
            return
        }

        fusedLocationClient.lastLocation.addOnSuccessListener { location: Location? ->
            if (location != null) {
                val current = GeoPoint(location.latitude, location.longitude)
                binding.mapSelector.controller.animateTo(current)
            } else {
                Toast.makeText(this, "Obteniendo señal GPS...", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun scheduleGeocoding() {
        geocodeRunnable?.let { geocodeHandler.removeCallbacks(it) }
        geocodeRunnable = Runnable {
            val center = binding.mapSelector.mapCenter as? GeoPoint
            center?.let { 
                selectedLat = it.latitude
                selectedLng = it.longitude
                reverseGeocode(it) 
            }
        }
        geocodeHandler.postDelayed(geocodeRunnable!!, 1000)
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
                        binding.tvSelectedAddress.text = addressLine
                    }
                }
            } catch (e: Exception) { }
        }.start()
    }

    override fun onResume() {
        super.onResume()
        binding.mapSelector.onResume()
    }

    override fun onPause() {
        super.onPause()
        binding.mapSelector.onPause()
    }
}
