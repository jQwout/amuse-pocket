package com.aiopenpocket.ui.memory

// @trace agent-memory-05e545

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.isActive
import kotlin.math.min
import kotlin.math.sqrt

/**
 * A small self-contained force-directed layout drawn on a [Canvas]. Repulsion between all nodes,
 * spring attraction along edges and a gentle pull to centre; damping settles it. Nodes can be
 * dragged; tapping a node fires [onNodeClick]. Deliberately dependency-free.
 */
@Composable
fun ForceDirectedGraph(
  graph: MemoryGraph,
  chatColor: Color,
  tagColor: Color,
  edgeColor: Color,
  labelColor: Color,
  modifier: Modifier = Modifier,
  onNodeClick: (MemoryNode) -> Unit = {},
) {
  val nodes = graph.nodes
  val measurer = rememberTextMeasurer()

  // Index lookup + edges expressed as index pairs for the tight simulation loop.
  val indexOf = remember(graph) { nodes.withIndex().associate { (i, n) -> n.id to i } }
  val edgePairs = remember(graph) {
    graph.edges.mapNotNull { e ->
      val a = indexOf[e.fromId]; val b = indexOf[e.toId]
      if (a != null && b != null) a to b else null
    }
  }

  val posX = remember(graph) { FloatArray(nodes.size) }
  val posY = remember(graph) { FloatArray(nodes.size) }
  val velX = remember(graph) { FloatArray(nodes.size) }
  val velY = remember(graph) { FloatArray(nodes.size) }
  // Силовые аккумуляторы переиспользуем между кадрами (обнуляем в начале прохода): иначе два
  // FloatArray(N) аллоцировались бы на КАЖДЫЙ кадр (~60/сек в движении) — мусор в GC на UI-потоке.
  val forceX = remember(graph) { FloatArray(nodes.size) }
  val forceY = remember(graph) { FloatArray(nodes.size) }

  var size by remember { mutableStateOf(IntSize.Zero) }
  var dragIndex by remember(graph) { mutableIntStateOf(-1) }
  // Bumped every simulation frame so the Canvas redraws.
  var frame by remember(graph) { mutableIntStateOf(0) }
  var initialized by remember(graph) { mutableStateOf(false) }
  // Once every node's velocity has damped to near-zero, the O(n²) force pass below is skipped
  // each frame (idle redraw loop only) until a drag perturbs a position again — otherwise this
  // burns full CPU forever while the memory graph screen is simply left open.
  var settled by remember(graph) { mutableStateOf(false) }

  fun radiusOf(node: MemoryNode): Float = when (node.kind) {
    MemoryNodeKind.CHAT -> 20f
    MemoryNodeKind.TAG -> min(34f, 14f + node.degree * 2f)
  }

  fun hitTest(point: Offset): Int {
    for (i in nodes.indices) {
      val dx = point.x - posX[i]
      val dy = point.y - posY[i]
      val r = radiusOf(nodes[i]) + 8f
      if (dx * dx + dy * dy <= r * r) return i
    }
    return -1
  }

  LaunchedSimulation(
    graph = graph,
    size = size,
    run = {
      if (nodes.isEmpty() || size == IntSize.Zero) return@LaunchedSimulation
      val w = size.width.toFloat()
      val h = size.height.toFloat()
      val cx = w / 2f
      val cy = h / 2f

      if (!initialized) {
        // Seed positions on a circle so the layout unfolds deterministically.
        val radius = min(w, h) / 3f
        for (i in nodes.indices) {
          val angle = (i.toFloat() / nodes.size) * (2f * Math.PI).toFloat()
          posX[i] = cx + radius * kotlin.math.cos(angle)
          posY[i] = cy + radius * kotlin.math.sin(angle)
          velX[i] = 0f
          velY[i] = 0f
        }
        initialized = true
      }

      // Idle: nothing is moving and nobody is dragging — skip the O(n²) force pass this frame.
      if (settled && dragIndex < 0) return@LaunchedSimulation

      val fx = forceX.also { it.fill(0f) }
      val fy = forceY.also { it.fill(0f) }

      for (i in nodes.indices) {
        for (j in i + 1 until nodes.size) {
          var dx = posX[i] - posX[j]
          var dy = posY[i] - posY[j]
          var d2 = dx * dx + dy * dy
          if (d2 < 1f) { d2 = 1f; dx = 1f; dy = 0f }
          val d = sqrt(d2)
          val rep = REPULSION / d2
          val ux = dx / d
          val uy = dy / d
          fx[i] += ux * rep; fy[i] += uy * rep
          fx[j] -= ux * rep; fy[j] -= uy * rep
        }
      }

      for ((a, b) in edgePairs) {
        val dx = posX[b] - posX[a]
        val dy = posY[b] - posY[a]
        val d = sqrt(dx * dx + dy * dy) + 0.01f
        val f = SPRING * (d - REST_LENGTH)
        val ux = dx / d
        val uy = dy / d
        fx[a] += ux * f; fy[a] += uy * f
        fx[b] -= ux * f; fy[b] -= uy * f
      }

      for (i in nodes.indices) {
        fx[i] += (cx - posX[i]) * GRAVITY
        fy[i] += (cy - posY[i]) * GRAVITY
      }

      for (i in nodes.indices) {
        if (i == dragIndex) continue
        velX[i] = ((velX[i] + fx[i]) * DAMPING).coerceIn(-MAX_VELOCITY, MAX_VELOCITY)
        velY[i] = ((velY[i] + fy[i]) * DAMPING).coerceIn(-MAX_VELOCITY, MAX_VELOCITY)
        posX[i] = (posX[i] + velX[i]).coerceIn(MARGIN, w - MARGIN)
        posY[i] = (posY[i] + velY[i]).coerceIn(MARGIN, h - MARGIN)
      }
      settled = dragIndex < 0 &&
        nodes.indices.all { i -> velX[i] * velX[i] + velY[i] * velY[i] < SETTLE_VELOCITY_SQ }
      frame++
    },
  )

  Canvas(
    modifier = modifier
      .onSizeChanged { size = it }
      .pointerInput(graph) {
        detectTapGestures { offset ->
          val i = hitTest(offset)
          if (i >= 0) onNodeClick(nodes[i])
        }
      }
      .pointerInput(graph) {
        detectDragGestures(
          onDragStart = { offset -> dragIndex = hitTest(offset) },
          onDragEnd = { dragIndex = -1 },
          onDragCancel = { dragIndex = -1 },
        ) { change, dragAmount ->
          change.consume()
          val i = dragIndex
          if (i >= 0) {
            posX[i] += dragAmount.x
            posY[i] += dragAmount.y
            velX[i] = 0f
            velY[i] = 0f
          }
        }
      },
  ) {
    // Reading `frame` here creates a snapshot dependency so the Canvas redraws each step.
    val redrawTrigger = frame
    if (!initialized || redrawTrigger < 0) return@Canvas

    for ((a, b) in edgePairs) {
      drawLine(
        color = edgeColor,
        start = Offset(posX[a], posY[a]),
        end = Offset(posX[b], posY[b]),
        strokeWidth = 1.5f,
      )
    }

    for (i in nodes.indices) {
      val node = nodes[i]
      val r = radiusOf(node)
      drawCircle(
        color = if (node.kind == MemoryNodeKind.CHAT) chatColor else tagColor,
        radius = r,
        center = Offset(posX[i], posY[i]),
      )
      val label = if (node.kind == MemoryNodeKind.CHAT && node.label.length > 16) {
        node.label.take(16) + "…"
      } else {
        node.label
      }
      val layout = measurer.measure(label, style = TextStyle(fontSize = 10.sp, color = labelColor))
      drawText(
        textLayoutResult = layout,
        topLeft = Offset(posX[i] - layout.size.width / 2f, posY[i] + r + 2f),
      )
    }
  }
}

/** Runs [run] once per animation frame while the graph/size are stable and the screen is shown. */
@Composable
private fun LaunchedSimulation(graph: MemoryGraph, size: IntSize, run: () -> Unit) {
  androidx.compose.runtime.LaunchedEffect(graph, size) {
    while (isActive) {
      withFrameNanos { }
      run()
    }
  }
}

private const val REPULSION = 120_000f
private const val SPRING = 0.03f
private const val REST_LENGTH = 140f
private const val GRAVITY = 0.005f
private const val DAMPING = 0.9f
private const val MAX_VELOCITY = 15f
private const val MARGIN = 28f
private const val SETTLE_VELOCITY_SQ = 0.01f
