# Allowed building blocks (Ketoy 0.4.20-alpha)

Anything not listed here fails the build with UnregisteredComposable, UnregisteredCall or NonKbcConstructor.

## Composables

Layout: Box, Column, Row, FlowRow, FlowColumn, Spacer, Scaffold, Surface, Card
Lists and grids: LazyColumn, LazyRow, LazyVerticalGrid, LazyHorizontalGrid, HorizontalPager, VerticalPager
Text and input: Text, TextField, Checkbox, Switch
Text ALWAYS with an explicit color, taken from the app's palette: `Text(text = ..., color = themeColor("text"))`; `themeColor("onAccent")` inside a Button. The default color renders transparent in the host.
Buttons: Button, IconButton
Media: Image, Icon, AsyncImage, Canvas
Other: TopAppBar (needs @OptIn(ExperimentalMaterial3Api::class)), AlertDialog, ModalBottomSheet, HorizontalDivider, VerticalDivider

NOT available (the build fails): OutlinedButton, TextButton, FloatingActionButton, OutlinedTextField, RadioButton, Slider,
CircularProgressIndicator, LinearProgressIndicator, NavigationBar, TabRow, DropdownMenu, Chip. Build them from
Box/Row/Surface/Card/Button with `background`; taps only through `Button` (no `Modifier.clickable`).

## Constructors you may call

RoundedCornerShape (including per-corner), KeyboardOptions, KeyboardActions, ButtonColors and ButtonElevation,
CardColors and CardElevation, TopAppBarColors, Offset, Size, Rect, CornerRadius, gradients, Path, Stroke,
GridCells.Fixed / GridCells.Adaptive / GridCells.FixedSize. Color(0xAARRGGBB) literals are fine.
User-defined `data class`, `enum class`, `sealed interface` and plain classes are fine.

## Modifiers

padding (all/horizontal/vertical/start-top-end-bottom, named args work), fillMaxWidth, fillMaxHeight, fillMaxSize,
size, width, height, widthIn, heightIn, wrapContentWidth/Height/Size, background(color, shape), clip(shape),
border(width, color, shape), shadow, clipToBounds, blur, alpha, offset, zIndex, testTag,
weight (inside Row/Column only), verticalScroll(rememberScrollState()), horizontalScroll(rememberScrollState()).
Shapes: RoundedCornerShape(dp), CircleShape, RectangleShape.

## Tokens (property reads that compile)

Colors: Color.Black White DarkGray Gray LightGray Red Green Blue Yellow Cyan Magenta Transparent; any other color via Color(0xFF......).
FontWeight: Thin ExtraLight Light Normal Medium SemiBold Bold ExtraBold Black. FontStyle: Normal Italic.
FontFamily: Default SansSerif Serif Monospace Cursive. TextAlign: Start End Center Justify Left Right.
TextOverflow: Clip Ellipsis Visible. TextDecoration: None Underline LineThrough.
Arrangement: Top Bottom Start End Center SpaceEvenly SpaceBetween SpaceAround, Arrangement.spacedBy(dp).
Alignment: TopStart TopCenter TopEnd CenterStart Center CenterEnd BottomStart BottomCenter BottomEnd,
Top CenterVertically Bottom, Start CenterHorizontally End.
ContentScale: Crop Fit FillBounds Inside None FillHeight FillWidth.
KeyboardType: Text Number Phone Uri Email Password NumberPassword Decimal. ImeAction: Default None Go Search Send Next Done Previous.
Sizes: `16.dp`, `14.sp`. MaterialTheme typography/colorScheme reads are NOT tokens - use explicit colors and sizes.
THE APP'S PALETTE - use it for every color: `themeColor("name")` (import dev.ketoy.runtime.compose.themeColor) gives the app's color
for the current theme, light or dark. Names: page (the background), card (a panel on the page), text, textMuted (secondary
lines), accent (the app's lime: highlights, progress, a done mark), onAccent (text on accent, and inside every Button),
line (borders, dividers). Works for `color =`, `background(themeColor("card"), RoundedCornerShape(20.dp))` and `border`.
Buttons already take the accent by themselves. A `Color(0xFF......)` literal is for a deliberate extra hue only (a red
warning, a category dot), never for text, cards or the page: fixed colors vanish in one of the themes.
Icons: do NOT use Icon/Icons - the app registers no icon set yet, so they render as an empty box. Use text or emoji labels ('+', '-', 'x') in Button/Text instead.

## State and control flow

remember { mutableStateOf(x) }, mutableIntStateOf, mutableFloatStateOf, mutableLongStateOf, mutableDoubleStateOf,
derivedStateOf, rememberSaveable, `var x by remember { ... }` with getValue/setValue imports.
if/when/for/while, forEach, forEachIndexed, repeat, listOf, mutableListOf, mapOf, map, filter, string templates, arithmetic.
Helper composables: another `@KetoyComposable @Composable fun` in the same file (not an entry point); they may take
String/Int/Boolean/Dp/Color/data class/List parameters, callback lambdas, and `content: @Composable () -> Unit` slots.

## Forbidden (hard compile errors)

Ranges of any kind (`0..7`, `until`, `downTo`, `step`, `.indices`): they compile but crash the app at runtime (no Int.rangeTo in the interpreter). Loop with `repeat(n) { i -> }` or `forEachIndexed`.
Helper composables, functions with parameters, lambda parameters, `Modifier.clickable`: they compile but crash the interpreter at runtime. ONE function, everything inline, taps through `Button`.
Numeric conversions (`toFloat()`, `toDouble()`, `toLong()`, `toInt()`) crash at runtime; `derivedStateOf` and nullable values (`null`, `?.`, `?:`) render as the text "null". Ints only, computed inline.
Fixed colors for text, cards or the page (`Color(0xFF1C1B1F)`, `Color.White`, `Color.Black` text): the page follows the app's light or dark theme, so they vanish in one of them. Use themeColor.
android.* APIs (Log, Context, Toast), reflection (::class.java, kotlin.reflect), java.io / File, GlobalScope, runBlocking,
network calls, LaunchedEffect with external APIs, ViewModels, any composable or function not listed above.
