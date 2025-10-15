package com.stocksheet.app

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Bundle
import android.util.Base64
import android.widget.*
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.ByteArrayOutputStream
import java.io.File
import java.text.SimpleDateFormat
import java.util.*

class MainActivity : ComponentActivity() {

    private lateinit var paneIn: LinearLayout; private lateinit var paneOut: LinearLayout; private lateinit var paneSettings: LinearLayout
    private lateinit var status: TextView
    private var inFile: File? = null; private var outFile: File? = null

    private val camIn = registerForActivityResult(ActivityResultContracts.TakePicture()){ ok ->
        if(!ok) inFile=null else findViewById<ImageView>(R.id.in_preview).setImageURI(Uri.fromFile(inFile))
    }
    private val camOut = registerForActivityResult(ActivityResultContracts.TakePicture()){ ok ->
        if(!ok) outFile=null else findViewById<ImageView>(R.id.out_preview).setImageURI(Uri.fromFile(outFile))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        paneIn = findViewById(R.id.paneIn); paneOut = findViewById(R.id.paneOut); paneSettings = findViewById(R.id.paneSettings)
        status = findViewById(R.id.txtStatus)

        findViewById<Button>(R.id.btnTabIn).setOnClickListener{ show("in") }
        findViewById<Button>(R.id.btnTabOut).setOnClickListener{ show("out") }
        findViewById<Button>(R.id.btnTabSettings).setOnClickListener{ show("settings") }

        findViewById<Button>(R.id.btnSaveUrl).setOnClickListener {
            val u = findViewById<EditText>(R.id.edtUrl).text.toString().trim()
            getSP().edit().putString("url", u).apply()
            toast("บันทึก URL แล้ว"); loadCategories()
        }
        findViewById<Button>(R.id.btnAddCat).setOnClickListener {
            val name = findViewById<EditText>(R.id.edtNewCat).text.toString().trim()
            if(name.isEmpty()){ toast("กรอกประเภท"); return@setOnClickListener }
            post(mapOf("action" to "addCategory", "name" to name)){ ok,_ -> runOnUiThread{ if(ok){ toast("เพิ่มแล้ว"); findViewById<EditText>(R.id.edtNewCat).setText(""); loadCategories() } else toast("เพิ่มไม่สำเร็จ") } }
        }

        findViewById<Button>(R.id.in_takePhoto).setOnClickListener { shot(true) }
        findViewById<Button>(R.id.out_takePhoto).setOnClickListener { shot(false) }
        findViewById<Button>(R.id.clearSign).setOnClickListener { findViewById<SignatureView>(R.id.signature).clear() }

        findViewById<Button>(R.id.in_submit).setOnClickListener { submitIn() }
        findViewById<Button>(R.id.out_submit).setOnClickListener { submitOut() }

        findViewById<EditText>(R.id.edtUrl).setText(getSP().getString("url",""))
        loadCategories()
    }

    private fun show(p:String){
        paneIn.visibility = if(p=="in") LinearLayout.VISIBLE else LinearLayout.GONE
        paneOut.visibility = if(p=="out") LinearLayout.VISIBLE else LinearLayout.GONE
        paneSettings.visibility = if(p=="settings") LinearLayout.VISIBLE else LinearLayout.GONE
    }
    private fun getSP() = getSharedPreferences("cfg", Context.MODE_PRIVATE)
    private fun toast(s:String)= Toast.makeText(this,s,Toast.LENGTH_SHORT).show()

    private fun web(): String {
        val u = getSP().getString("url","") ?: ""
        if(u.isEmpty()){ runOnUiThread{ toast("กรอก URL ในแท็บตั้งค่าก่อน"); show("settings") } }
        return u.trim().trimEnd('/')
    }

    private fun shot(isIn:Boolean){
        if(ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA)!=PackageManager.PERMISSION_GRANTED){
            requestPermissions(arrayOf(Manifest.permission.CAMERA), 9); return
        }
        val f = File.createTempFile((if(isIn) "in_" else "out_")+SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date()), ".jpg", externalCacheDir)
        val uri = FileProvider.getUriForFile(this, "${packageName}.fileprovider", f)
        if(isIn){ inFile=f; camIn.launch(uri) } else { outFile=f; camOut.launch(uri) }
    }

    private fun bmpToB64(b: Bitmap?): String {
        if(b==null) return ""
        val baos = ByteArrayOutputStream(); b.compress(Bitmap.CompressFormat.JPEG, 85, baos)
        val enc = Base64.encodeToString(baos.toByteArray(), Base64.NO_WRAP)
        return "data:image/jpeg;base64,$enc"
    }
    private fun fileToB64(f: File?): String = if(f==null || !f.exists()) "" else bmpToB64(BitmapFactory.decodeFile(f.absolutePath))

    private fun client() = OkHttpClient()

    private fun post(payload: Map<String,Any>, cb:(Boolean,String)->Unit){
        val url = web(); if(url.isEmpty()){ cb(false,"no url"); return }
        val json = payload.entries.joinToString(prefix="{", postfix="}"){
            ""${it.key}":"${it.value.toString().replace("\","\\").replace(""","\"")}""
        }
        val req = Request.Builder().url(url).post(json.toRequestBody("application/json".toMediaTypeOrNull())).build()
        Thread{
            try{
                val res = client().newCall(req).execute(); cb(res.isSuccessful, res.body?.string() ?: "")
            }catch(e:Exception){ cb(false, e.message ?: "error") }
        }.start()
    }

    private fun loadCategories(){
        val url = web(); if(url.isEmpty()) return
        Thread{
            try{
                val res = client().newCall(Request.Builder().url("$url?action=categories").build()).execute()
                val body = res.body?.string() ?: ""
                val cats = Regex('"'+ "categories" +'"\s*:\s*\[(.*?)\]').find(body)?.groupValues?.getOrNull(1)
                val list = mutableListOf<String>()
                if(cats!=null) Regex('"([^"]+)"').findAll(cats).forEach{ list.add(it.groupValues[1]) }
                runOnUiThread{
                    val ad = ArrayAdapter(this, android.R.layout.simple_spinner_item, list).apply{ setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item) }
                    findViewById<Spinner>(R.id.in_category).adapter = ad
                    findViewById<Spinner>(R.id.out_category).adapter = ad
                    val wrap = findViewById<LinearLayout>(R.id.catList); wrap.removeAllViews()
                    list.forEach { name ->
                        val row = LinearLayout(this); row.orientation = LinearLayout.HORIZONTAL
                        val tv = TextView(this); tv.text = name; tv.textSize = 16f
                        val del = Button(this); del.text = "ลบ"; del.setOnClickListener {
                            post(mapOf("action" to "delCategory", "name" to name)){ ok,_ -> runOnUiThread{ if(ok){ toast("ลบแล้ว"); loadCategories() } } }
                        }
                        row.addView(tv); row.addView(del); wrap.addView(row)
                    }
                }
            }catch(e:Exception){
                runOnUiThread{ status.text = "โหลดประเภทไม่สำเร็จ: ${e.message}" }
            }
        }.start()
    }

    private fun submitIn(){
        val payload = mapOf(
            "action" to "inbound",
            "category" to (findViewById<Spinner>(R.id.in_category).selectedItem?.toString() ?: ""),
            "name" to findViewById<EditText>(R.id.in_name).text.toString(),
            "brand" to findViewById<EditText>(R.id.in_brand).text.toString(),
            "model" to findViewById<EditText>(R.id.in_model).text.toString(),
            "sn" to findViewById<EditText>(R.id.in_sn).text.toString(),
            "qty" to findViewById<EditText>(R.id.in_qty).text.toString(),
            "source" to findViewById<EditText>(R.id.in_source).text.toString(),
            "note" to findViewById<EditText>(R.id.in_note).text.toString(),
            "checker" to findViewById<EditText>(R.id.in_checker).text.toString(),
            "datetime" to findViewById<EditText>(R.id.in_datetime).text.toString(),
            "photoBase64" to fileToB64(inFile)
        )
        status.text = "กำลังส่งรับเข้า..."
        post(payload){ ok,_ -> runOnUiThread{ status.text = if(ok) "บันทึกรับเข้า ✅" else "ส่งไม่สำเร็จ" } }
    }

    private fun submitOut(){
        val signBmp = findViewById<SignatureView>(R.id.signature).toBitmap()
        val payload = mapOf(
            "action" to "outbound",
            "category" to (findViewById<Spinner>(R.id.out_category).selectedItem?.toString() ?: ""),
            "name" to findViewById<EditText>(R.id.out_name).text.toString(),
            "brand" to findViewById<EditText>(R.id.out_brand).text.toString(),
            "model" to findViewById<EditText>(R.id.out_model).text.toString(),
            "sn" to findViewById<EditText>(R.id.out_sn).text.toString(),
            "qty" to findViewById<EditText>(R.id.out_qty).text.toString(),
            "source" to findViewById<EditText>(R.id.out_source).text.toString(),
            "note" to findViewById<EditText>(R.id.out_note).text.toString(),
            "outBy" to findViewById<EditText>(R.id.out_by).text.toString(),
            "datetime" to findViewById<EditText>(R.id.out_datetime).text.toString(),
            "photoBase64" to fileToB64(outFile),
            "signBase64" to bmpToB64(signBmp)
        )
        status.text = "กำลังส่งนำออก..."
        post(payload){ ok,_ -> runOnUiThread{ status.text = if(ok) "บันทึกนำออก ✅" else "ส่งไม่สำเร็จ" } }
    }
}
