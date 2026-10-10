package com.sensirion.sps30.wear

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.wear.compose.material.MaterialTheme
import androidx.wear.compose.material.Scaffold
import androidx.wear.compose.material.Text
import androidx.wear.compose.material.TimeText
import com.sensirion.sps30.wear.relay.LatestReading
import com.sensirion.sps30.wear.relay.LatestReadingStore
import kotlinx.coroutines.delay

/**
 * The watch app / complication tap target. Shows the current PM2.5 with its
 * air-quality color and the same 1-hour sparkline the Tile draws, so tapping the
 * complication lands on the full readout. Reads the phone-relayed value from
 * [LatestReadingStore]; never connects to the daemon.
 */
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { MaterialTheme { Sps30WatchApp() } }
    }
}

@Composable
private fun Sps30WatchApp() {
    val context = LocalContext.current
    var latest by remember { mutableStateOf<LatestReading?>(null) }

    // Cheap poll of the relayed value; the Data Layer listener persists it.
    LaunchedEffect(Unit) {
        while (true) {
            latest = LatestReadingStore.load(context)
            delay(2000)
        }
    }

    Scaffold(timeText = { TimeText() }) {
        Column(
            modifier = Modifier.fillMaxSize().padding(horizontal = 12.dp, vertical = 8.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            val reading = latest
            val fresh = reading != null && !reading.pm2_5.isNaN() &&
                System.currentTimeMillis() - reading.sentAt <= STALE_AFTER_MS

            if (reading == null) {
                Text(
                    text = context.getString(R.string.waiting_phone),
                    color = MaterialTheme.colors.onSurfaceVariant,
                )
            } else {
                val aq = AirQuality.of(reading.pm2_5)
                val valueColor = if (fresh) Color(aq.color) else MaterialTheme.colors.onSurfaceVariant

                // Label kept clearly apart from the number (spaced + tracked out, a
                // muted caption style) so it never reads as part of the value.
                Text(
                    text = Format.PM_LABEL,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Medium,
                    letterSpacing = 3.sp,
                    color = MaterialTheme.colors.onSurfaceVariant,
                )
                Spacer(Modifier.height(10.dp))
                Text(
                    text = Format.pm(reading.pm2_5),
                    fontSize = 46.sp,
                    fontWeight = FontWeight.Bold,
                    color = valueColor,
                )
                Text(
                    text = Format.PM_UNIT,
                    fontSize = 12.sp,
                    color = MaterialTheme.colors.onSurfaceVariant,
                )
                if (fresh && reading.spark.size >= 2) {
                    Spacer(Modifier.height(8.dp))
                    Sparkline(reading.spark)
                }
                Spacer(Modifier.height(6.dp))
                Text(
                    text = if (fresh) aq.label else "stale",
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Medium,
                    color = valueColor,
                )
            }
        }
    }
}

/** A filled 1-hour PM2.5 sparkline, colored by the current air-quality level -
 *  the Compose twin of the Tile's [com.sensirion.sps30.wear.tile.SparklineRenderer]. */
@Composable
private fun Sparkline(values: FloatArray) {
    val color = Color(AirQuality.of(values.last().toDouble()).color)
    Spacer(
        modifier = Modifier
            .width(132.dp)
            .height(40.dp)
            .drawBehind {
                val pad = 3f
                val w = size.width - 2 * pad
                val h = size.height - 2 * pad
                var lo = values.min()
                var hi = values.max()
                if (hi - lo < 1e-3f) { lo -= 1f; hi += 1f }
                val range = hi - lo
                fun px(i: Int) = pad + w * i / (values.size - 1)
                fun py(v: Float) = pad + h * (1f - (v - lo) / range)

                val line = Path().apply {
                    moveTo(px(0), py(values[0]))
                    for (i in 1 until values.size) lineTo(px(i), py(values[i]))
                }
                val fill = Path().apply {
                    addPath(line)
                    lineTo(px(values.size - 1), pad + h)
                    lineTo(px(0), pad + h)
                    close()
                }
                drawPath(fill, color = color.copy(alpha = 0.24f))
                drawPath(
                    line,
                    color = color,
                    style = Stroke(width = 3f, cap = StrokeCap.Round, join = StrokeJoin.Round),
                )
            },
    )
}

private const val STALE_AFTER_MS = 10 * 60 * 1000L
