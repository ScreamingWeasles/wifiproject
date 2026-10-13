package com.officewifi.inspector

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Paint
import android.graphics.pdf.PdfDocument
import android.graphics.pdf.PdfRenderer
import android.net.Uri
import android.os.ParcelFileDescriptor
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import kotlin.math.roundToInt

private data class PlanMark(val x: Float, val y: Float, val kind: String, val label: String, val rssi: Int, val bssid: String)
private data class PlanWall(val x1: Float, val y1: Float, val x2: Float, val y2: Float)
private fun clamp(v: Float) = v.coerceIn(0f, 1f)

@Composable
internal fun FloorPlanScreen(connectedRssi: Int?, connectedBssid: String?) {
    val context = LocalContext.current
    val marks = remember { mutableStateListOf<PlanMark>() }
    val walls = remember { mutableStateListOf<PlanWall>() }
    var background by remember { mutableStateOf<Bitmap?>(null) }
    var mode by remember { mutableStateOf("Measure") }
    var label by remember { mutableStateOf("Room / checkpoint") }
    var message by remember { mutableStateOf("Import a plan or draw one. Tap the map to add a reading.") }
    var dragStart by remember { mutableStateOf<Offset?>(null) }
    var dragEnd by remember { mutableStateOf<Offset?>(null) }
    fun save() {
        try {
            val obj = JSONObject()
            obj.put("marks", JSONArray().apply { marks.forEach { m -> put(JSONObject().apply { put("x", m.x.toDouble()); put("y", m.y.toDouble()); put("kind",m.kind); put("label",m.label); put("rssi",m.rssi); put("bssid",m.bssid) }) } })
            obj.put("walls", JSONArray().apply { walls.forEach { w -> put(JSONObject().apply { put("x1",w.x1.toDouble());put("y1",w.y1.toDouble());put("x2",w.x2.toDouble());put("y2",w.y2.toDouble()) }) } })
            File(context.filesDir,"weasels-plan.json").writeText(obj.toString())
            background?.let { bmp -> File(context.filesDir,"weasels-plan.png").outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG,90,it) } }
            if (background == null) File(context.filesDir,"weasels-plan.png").delete()
            message = "Floor plan saved on this phone."
        } catch (e: Exception) { message = "Save failed: ${e.message}" }
    }
    fun load() {
        try {
            marks.clear(); walls.clear()
            val file = File(context.filesDir,"weasels-plan.json")
            if (!file.exists()) { message = "No saved plan yet"; return }
            val obj = JSONObject(file.readText())
            val ma = obj.getJSONArray("marks")
            for (i in 0 until ma.length()) { val m=ma.getJSONObject(i); marks.add(PlanMark(m.getDouble("x").toFloat(),m.getDouble("y").toFloat(),m.getString("kind"),m.getString("label"),m.getInt("rssi"),m.getString("bssid"))) }
            val wa=obj.getJSONArray("walls")
            for (i in 0 until wa.length()) { val w=wa.getJSONObject(i); walls.add(PlanWall(w.getDouble("x1").toFloat(),w.getDouble("y1").toFloat(),w.getDouble("x2").toFloat(),w.getDouble("y2").toFloat())) }
            val image=File(context.filesDir,"weasels-plan.png")
            background = if (image.exists()) BitmapFactory.decodeFile(image.absolutePath) else null
            message = "Loaded saved floor plan (${marks.size} markers)."
        } catch (e: Exception) { message = "Load failed: ${e.message}" }
    }
    val importer = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri: Uri? ->
        if (uri != null) try {
            val mime = context.contentResolver.getType(uri).orEmpty()
            val bmp = if (mime == "application/pdf") {
                context.contentResolver.openFileDescriptor(uri,"r")?.use { fd ->
                    PdfRenderer(fd).use { renderer ->
                        renderer.openPage(0).use { page ->
                            val scale = minOf(1f, 1600f / maxOf(page.width,page.height))
                            val result=Bitmap.createBitmap((page.width*scale).roundToInt().coerceAtLeast(1),(page.height*scale).roundToInt().coerceAtLeast(1),Bitmap.Config.ARGB_8888)
                            result.eraseColor(android.graphics.Color.WHITE)
                            page.render(result,null,null,PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                            result
                        }
                    }
                }
            } else context.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it) }
            if (bmp != null) { background=bmp; message="Plan imported. Use Measure to add survey points, or Wall to draw over it." }
            else message="Could not decode that file"
        } catch (e: Exception) { message="Import failed: ${e.message}" }
    }
    val pdfExport = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/pdf")) { uri: Uri? ->
        if (uri != null) try {
            val doc=PdfDocument()
            val page=doc.startPage(PdfDocument.PageInfo.Builder(595,842,1).create())
            val canvas=page.canvas
            val p=Paint(Paint.ANTI_ALIAS_FLAG)
            p.color=android.graphics.Color.BLACK;p.textSize=20f;p.isFakeBoldText=true
            canvas.drawText("Screaming Weasels Wi-Fi Teaser",36f,48f,p)
            p.textSize=12f;p.isFakeBoldText=false
            canvas.drawText("Make Your Wi-Fi Squeal!  |  Floor Plan Survey",36f,70f,p)
            canvas.drawText("Measurements: ${marks.count { it.kind == "Measure" }}   AP markers: ${marks.count { it.kind == "AP" }}",36f,92f,p)
            val left=36f;val top=112f;val width=523f;val height=340f
            drawPlanOnAndroidCanvas(canvas,background,walls.toList(),marks.toList(),left,top,width,height)
            var y=477f
            p.textSize=12f;p.isFakeBoldText=true;canvas.drawText("Survey readings (dBm)",36f,y,p);y+=22f;p.isFakeBoldText=false
            for (m in marks.filter { it.kind=="Measure" }.take(19)) {
                val safe=m.label.take(28).replace("\n"," ")
                canvas.drawText("$safe: ${m.rssi} dBm  |  ${m.bssid.take(18)}",36f,y,p);y+=17f
            }
            if (marks.count { it.kind=="Measure" }>19) canvas.drawText("Additional readings omitted from this one-page summary.",36f,y,p)
            p.textSize=10f;canvas.drawText("Client-observed readings; map locations are manually selected. Not an AP controller health report.",36f,817f,p)
            doc.finishPage(page)
            context.contentResolver.openOutputStream(uri)?.use { doc.writeTo(it) }
            doc.close();message="PDF report exported."
        } catch (e: Exception) { message="PDF export failed: ${e.message}" }
    }
    Column(verticalArrangement=Arrangement.spacedBy(10.dp)) {
        Text("Floor Plan & Coverage",style=MaterialTheme.typography.headlineSmall)
        Text("Import a plan or draw walls. Tap to place a live Wi-Fi reading, AP marker, or room label.",style=MaterialTheme.typography.bodySmall)
        Row(horizontalArrangement=Arrangement.spacedBy(8.dp)) {
            Button(onClick={ importer.launch("*/*") }) { Text("Import JPG / PNG / PDF") }
            OutlinedButton(onClick={ background=null;marks.clear();walls.clear();message="Blank drawing started." }) { Text("New drawing") }
        }
        Text("Tool")
        SingleChoiceSegmentedButtonRow(modifier=Modifier.fillMaxWidth()) {
            listOf("Measure","Wall","AP","Label").forEachIndexed { i, choice ->
                SegmentedButton(selected=mode==choice,onClick={mode=choice},shape=SegmentedButtonDefaults.itemShape(i,4)) { Text(choice) }
            }
        }
        OutlinedTextField(value=label,onValueChange={label=it},label={Text("Room / AP label")},singleLine=true,modifier=Modifier.fillMaxWidth())
        val pointColor = Color(0xFF16834B)
        Canvas(modifier=Modifier.fillMaxWidth().height(360.dp)
            .pointerInput(mode,connectedRssi,connectedBssid,label) {
                detectTapGestures { at ->
                    if (mode!="Wall") {
                        val kind=mode
                        marks.add(PlanMark(clamp(at.x/size.width),clamp(at.y/size.height),kind,label,connectedRssi?:-100,connectedBssid?:"Unknown"))
                        message=if (kind=="Measure") "Recorded ${connectedRssi ?: "unknown"} dBm at $label" else "$kind marker added"
                    }
                }
            }
            .pointerInput(mode) {
                if (mode=="Wall") detectDragGestures(onDragStart={dragStart=it;dragEnd=it},onDragEnd={
                    val a=dragStart;val b=dragEnd
                    if (a!=null&&b!=null) walls.add(PlanWall(clamp(a.x/size.width),clamp(a.y/size.height),clamp(b.x/size.width),clamp(b.y/size.height)))
                    dragStart=null;dragEnd=null
                },onDragCancel={dragStart=null;dragEnd=null},onDrag={change,_->dragEnd=change.position;change.consume()})
            }) {
            drawIntoCanvas { cv ->
                drawPlanOnAndroidCanvas(cv.nativeCanvas,background,walls.toList(),marks.toList(),0f,0f,size.width,size.height)
            }
            val a=dragStart;val b=dragEnd
            if (a!=null&&b!=null) drawLine(Color.Blue,a,b,strokeWidth=5f)
        }
        Text(message,style=MaterialTheme.typography.bodySmall)
        Text("Measurements: ${marks.count { it.kind=="Measure" }} · AP markers: ${marks.count { it.kind=="AP" }} · Walls: ${walls.size}",style=MaterialTheme.typography.bodySmall)
        Row(horizontalArrangement=Arrangement.spacedBy(8.dp)) {
            Button(onClick={save()}) { Text("Save plan") }
            OutlinedButton(onClick={load()}) { Text("Load saved") }
            OutlinedButton(onClick={pdfExport.launch("screaming-weasels-survey.pdf")}) { Text("Export PDF") }
        }
        Row(horizontalArrangement=Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick={if(marks.isNotEmpty())marks.removeAt(marks.lastIndex) else if(walls.isNotEmpty())walls.removeAt(walls.lastIndex)}) { Text("Undo marker") }
            Text("Colors show measured signal at tapped locations; not interpolated RF coverage.",style=MaterialTheme.typography.bodySmall)
        }
    }
}

private fun drawPlanOnAndroidCanvas(canvas: android.graphics.Canvas, image: Bitmap?, walls: List<PlanWall>, marks: List<PlanMark>, left: Float, top: Float, width: Float, height: Float) {
    val p=Paint(Paint.ANTI_ALIAS_FLAG)
    p.color=android.graphics.Color.rgb(247,249,252);canvas.drawRect(left,top,left+width,top+height,p)
    if (image!=null) { p.alpha=220;canvas.drawBitmap(image,null,android.graphics.RectF(left,top,left+width,top+height),p);p.alpha=255 }
    p.color=android.graphics.Color.DKGRAY;p.strokeWidth=5f
    walls.forEach { w->canvas.drawLine(left+w.x1*width,top+w.y1*height,left+w.x2*width,top+w.y2*height,p) }
    marks.forEach { m ->
        val x=left+m.x*width;val y=top+m.y*height
        p.color=when(m.kind) { "AP"->android.graphics.Color.rgb(36,89,210);"Label"->android.graphics.Color.rgb(60,60,60);else->if(m.rssi>=-60) android.graphics.Color.rgb(22,131,75) else if(m.rssi>=-70) android.graphics.Color.rgb(224,159,0) else android.graphics.Color.rgb(205,50,50) }
        p.alpha=if(m.kind=="Measure") 65 else 255
        canvas.drawCircle(x,y,if(m.kind=="Measure") 34f else 10f,p)
        p.alpha=255;p.textSize=12f;p.isFakeBoldText=true
        canvas.drawText(if(m.kind=="Measure") "${m.rssi} dBm" else m.label.take(18),x+10f,y-10f,p)
    }
    p.style=Paint.Style.STROKE;p.color=android.graphics.Color.LTGRAY;p.strokeWidth=2f
    canvas.drawRect(left,top,left+width,top+height,p)
    p.style=Paint.Style.FILL
}
