package com.igeeta.igpod

import android.os.Bundle
import androidx.activity.enableEdgeToEdge
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.recyclerview.widget.RecyclerView
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import com.igeeta.igpod.logic.gramophoneApplication
import com.igeeta.igpod.logic.ui.BaseActivity

class Reflections : BaseActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContentView(R.layout.activity_reflections)
        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.main)) { v, insets ->
            val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.setPadding(systemBars.left, systemBars.top, systemBars.right, systemBars.bottom)
            insets
        }
        val r = findViewById<RecyclerView>(R.id.recyclerview)
        CoroutineScope(Dispatchers.Default).launch {
            gramophoneApplication.reader.refresh()
            withContext(Dispatchers.Main) {
                r.adapter =
                    ReflectionAdapter(gramophoneApplication.reader, null, { r.adapter = it }, false)
            }
        }
    }
}