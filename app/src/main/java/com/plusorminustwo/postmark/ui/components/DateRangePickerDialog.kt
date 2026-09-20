package com.plusorminustwo.postmark.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.plusorminustwo.postmark.domain.calendar.DayRange
import com.plusorminustwo.postmark.domain.calendar.DayRole
import com.plusorminustwo.postmark.domain.calendar.MonthGrid
import kotlinx.coroutines.launch
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.YearMonth
import java.time.format.TextStyle
import java.time.temporal.WeekFields
import java.util.Locale

// ── DateRangePickerDialog ─────────────────────────────────────────────────────

/** Which grid the calendar body is showing. */
private enum class PickerMode { DAYS, MONTHS, YEARS }

/** The most week rows any month needs — the body reserves this many so it never resizes. */
private const val WeekRows = 6

/**
 * Date-range picker: one month at a time, swiped left/right, with the month name and the
 * year each tappable to jump somewhere far away. Tapping a day sets the start, tapping a
 * later day sets the end (see [MonthGrid.tap] for the full rule).
 *
 * It lives in an [AlertDialog] rather than a bottom sheet on purpose: a sheet treats
 * vertical drags over its content as a drag on the sheet itself, so the old vertically
 * scrolling Material3 DateRangePicker dismissed the sheet about as often as it scrolled.
 * Paging horizontally has no such conflict, and a dialog only closes on an explicit tap
 * outside or Back. Shared by ThreadScreen's date-jump and the backup Export screen.
 *
 * @param onSelect Called with the inclusive start/end days once both are picked.
 * @param onDismiss Called on Cancel, Back, or a tap outside.
 */
@Composable
fun DateRangePickerDialog(
    onSelect: (LocalDate, LocalDate) -> Unit,
    onDismiss: () -> Unit
) {
    val today = remember { LocalDate.now() }
    // Wide enough for any SMS archive, plus next year so scheduled messages are reachable.
    val years = remember(today) { 1990..(today.year + 1) }
    val locale = Locale.getDefault()
    val firstDayOfWeek = remember(locale) { WeekFields.of(locale).firstDayOfWeek }

    var range by remember { mutableStateOf(DayRange()) }
    var mode by remember { mutableStateOf(PickerMode.DAYS) }
    val pagerState = rememberPagerState(
        initialPage = MonthGrid.pageOf(YearMonth.from(today), years)
    ) { MonthGrid.pageCount(years) }
    val scope = rememberCoroutineScope()
    val visibleMonth = MonthGrid.monthAt(pagerState.currentPage, years)

    fun jumpTo(month: YearMonth) {
        mode = PickerMode.DAYS
        scope.launch { pagerState.scrollToPage(MonthGrid.pageOf(month, years)) }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(rangeHeadline(range, locale)) },
        text = {
            // Every cell is a square a seventh of the dialog's width, and the body is six
            // of those tall — so the day circles stay round and the height never depends on
            // a hardcoded number, whatever width the dialog gets.
            BoxWithConstraints(modifier = Modifier.fillMaxWidth()) {
                val cell = maxWidth / 7
                Column(modifier = Modifier.fillMaxWidth()) {
                    CalendarHeader(
                        month = visibleMonth,
                        mode = mode,
                        locale = locale,
                        onToggleMonths = {
                            mode = if (mode == PickerMode.MONTHS) PickerMode.DAYS else PickerMode.MONTHS
                        },
                        onToggleYears = {
                            mode = if (mode == PickerMode.YEARS) PickerMode.DAYS else PickerMode.YEARS
                        },
                        onPrev = { scope.launch { pagerState.animateScrollToPage(pagerState.currentPage - 1) } },
                        onNext = { scope.launch { pagerState.animateScrollToPage(pagerState.currentPage + 1) } },
                        canGoPrev = pagerState.currentPage > 0,
                        canGoNext = pagerState.currentPage < pagerState.pageCount - 1
                    )
                    // Kept in the layout (just invisible) outside day mode so switching to
                    // the month or year grid does not make the dialog jump by a row's height.
                    WeekdayHeader(
                        firstDayOfWeek = firstDayOfWeek,
                        locale = locale,
                        modifier = Modifier.alpha(if (mode == PickerMode.DAYS) 1f else 0f)
                    )
                    Box(modifier = Modifier.height(cell * WeekRows)) {
                        when (mode) {
                            PickerMode.DAYS ->
                                HorizontalPager(state = pagerState) { page ->
                                    MonthDays(
                                        month = MonthGrid.monthAt(page, years),
                                        firstDayOfWeek = firstDayOfWeek,
                                        range = range,
                                        today = today,
                                        cell = cell,
                                        onDayClick = { range = MonthGrid.tap(range, it) }
                                    )
                                }
                            PickerMode.MONTHS -> MonthPicker(
                                selected = visibleMonth,
                                locale = locale,
                                cell = cell,
                                onPick = { jumpTo(visibleMonth.withMonth(it)) }
                            )
                            PickerMode.YEARS -> YearPicker(
                                years = years,
                                selected = visibleMonth.year,
                                cell = cell,
                                onPick = { jumpTo(visibleMonth.withYear(it)) }
                            )
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    val start = range.start ?: return@TextButton
                    val end = range.end ?: return@TextButton
                    onSelect(start, end)
                },
                enabled = range.isComplete
            ) { Text("Select") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } }
    )
}

/** "Select a start date" / "May 1 – May 3, 2026" — what the picked range currently is. */
private fun rangeHeadline(range: DayRange, locale: Locale): String {
    val start = range.start ?: return "Select a start date"
    val end = range.end ?: return "${formatDay(start, locale)} – select an end date"
    return "${formatDay(start, locale)} – ${formatDay(end, locale)}"
}

private fun formatDay(day: LocalDate, locale: Locale): String =
    "${day.month.getDisplayName(TextStyle.SHORT, locale)} ${day.dayOfMonth}, ${day.year}"

// ── Header ────────────────────────────────────────────────────────────────────

/** Month and year as separate tappable labels, with previous/next month arrows. */
@Composable
private fun CalendarHeader(
    month: YearMonth,
    mode: PickerMode,
    locale: Locale,
    onToggleMonths: () -> Unit,
    onToggleYears: () -> Unit,
    onPrev: () -> Unit,
    onNext: () -> Unit,
    canGoPrev: Boolean,
    canGoNext: Boolean
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically
    ) {
        HeaderChip(
            label = month.month.getDisplayName(TextStyle.FULL, locale),
            expanded = mode == PickerMode.MONTHS,
            onClick = onToggleMonths
        )
        Spacer(Modifier.width(4.dp))
        HeaderChip(
            label = month.year.toString(),
            expanded = mode == PickerMode.YEARS,
            onClick = onToggleYears
        )
        Spacer(Modifier.weight(1f))
        IconButton(onClick = onPrev, enabled = canGoPrev && mode == PickerMode.DAYS) {
            Icon(Icons.AutoMirrored.Filled.KeyboardArrowLeft, contentDescription = "Previous month")
        }
        IconButton(onClick = onNext, enabled = canGoNext && mode == PickerMode.DAYS) {
            Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = "Next month")
        }
    }
}

/** One tappable header label ("September" / "2026") with a caret that flips when open. */
@Composable
private fun HeaderChip(label: String, expanded: Boolean, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .clip(RoundedCornerShape(50))
            .clickable(onClick = onClick)
            .padding(horizontal = 8.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.titleMedium,
            color = if (expanded) MaterialTheme.colorScheme.primary
            else MaterialTheme.colorScheme.onSurface
        )
        Icon(
            imageVector = Icons.Default.ArrowDropDown,
            contentDescription = null,
            tint = if (expanded) MaterialTheme.colorScheme.primary
            else MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

// ── Day grid ──────────────────────────────────────────────────────────────────

/** The S M T W T F S strip, rotated to the locale's first day of week. */
@Composable
private fun WeekdayHeader(firstDayOfWeek: DayOfWeek, locale: Locale, modifier: Modifier = Modifier) {
    Row(modifier = modifier.fillMaxWidth()) {
        MonthGrid.weekdays(firstDayOfWeek).forEach { day ->
            Text(
                text = day.getDisplayName(TextStyle.NARROW, locale),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
                modifier = Modifier.weight(1f)
            )
        }
    }
}

/** One month's days, laid out as week rows. */
@Composable
private fun MonthDays(
    month: YearMonth,
    firstDayOfWeek: DayOfWeek,
    range: DayRange,
    today: LocalDate,
    cell: Dp,
    onDayClick: (LocalDate) -> Unit
) {
    Column(modifier = Modifier.fillMaxWidth()) {
        MonthGrid.cells(month, firstDayOfWeek).chunked(7).forEach { week ->
            Row(modifier = Modifier.fillMaxWidth()) {
                week.forEach { day ->
                    DayCell(
                        day = day,
                        role = day?.let { MonthGrid.roleOf(it, range) } ?: DayRole.NONE,
                        isToday = day == today,
                        cell = cell,
                        onClick = onDayClick
                    )
                }
                repeat(7 - week.size) { Spacer(Modifier.size(cell)) }
            }
        }
    }
}

/**
 * One day. The connecting band between endpoints is drawn as two half-width boxes behind
 * the number, so a START fills only its trailing half and an END only its leading half —
 * the band lines up across cells without any hardcoded offsets.
 */
@Composable
private fun DayCell(
    day: LocalDate?,
    role: DayRole,
    isToday: Boolean,
    cell: Dp,
    onClick: (LocalDate) -> Unit
) {
    Box(
        modifier = Modifier.size(cell),
        contentAlignment = Alignment.Center
    ) {
        if (day != null) {
            val band = MaterialTheme.colorScheme.secondaryContainer
            val leadingBand = role == DayRole.MIDDLE || role == DayRole.END
            val trailingBand = role == DayRole.MIDDLE || role == DayRole.START
            Row(modifier = Modifier.fillMaxSize()) {
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxSize()
                        .background(if (leadingBand) band else Color.Transparent)
                )
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxSize()
                        .background(if (trailingBand) band else Color.Transparent)
                )
            }
            val isEndpoint = role == DayRole.START || role == DayRole.END || role == DayRole.SINGLE
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(2.dp)
                    .clip(CircleShape)
                    .then(
                        if (isEndpoint) Modifier.background(MaterialTheme.colorScheme.primary)
                        else Modifier
                    )
                    .then(
                        if (isToday && !isEndpoint)
                            Modifier.border(1.dp, MaterialTheme.colorScheme.primary, CircleShape)
                        else Modifier
                    )
                    .clickable { onClick(day) },
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = day.dayOfMonth.toString(),
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = if (isEndpoint) FontWeight.Bold else FontWeight.Normal,
                    color = when {
                        isEndpoint -> MaterialTheme.colorScheme.onPrimary
                        role == DayRole.MIDDLE -> MaterialTheme.colorScheme.onSecondaryContainer
                        else -> MaterialTheme.colorScheme.onSurface
                    }
                )
            }
        }
    }
}

// ── Month / year jump grids ───────────────────────────────────────────────────

/** Twelve month names, three per row; picking one keeps the year and returns to the days. */
@Composable
private fun MonthPicker(selected: YearMonth, locale: Locale, cell: Dp, onPick: (Int) -> Unit) {
    LazyVerticalGrid(columns = GridCells.Fixed(3), modifier = Modifier.fillMaxSize()) {
        items((1..12).toList()) { monthValue ->
            JumpCell(
                label = YearMonth.of(selected.year, monthValue)
                    .month.getDisplayName(TextStyle.SHORT, locale),
                selected = monthValue == selected.monthValue,
                cell = cell,
                onClick = { onPick(monthValue) }
            )
        }
    }
}

/** Every year in range, three per row, opened scrolled to the one on screen. */
@Composable
private fun YearPicker(years: IntRange, selected: Int, cell: Dp, onPick: (Int) -> Unit) {
    val gridState = rememberLazyGridState()
    LaunchedEffect(selected) {
        gridState.scrollToItem((selected - years.first - 1).coerceAtLeast(0))
    }
    LazyVerticalGrid(
        columns = GridCells.Fixed(3),
        state = gridState,
        modifier = Modifier.fillMaxSize()
    ) {
        items(years.toList()) { year ->
            JumpCell(
                label = year.toString(),
                selected = year == selected,
                cell = cell,
                onClick = { onPick(year) }
            )
        }
    }
}

/** One cell of the month or year jump grid. */
@Composable
private fun JumpCell(label: String, selected: Boolean, cell: Dp, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .height(cell)
            .padding(4.dp)
            .clip(RoundedCornerShape(50))
            .then(
                if (selected) Modifier.background(MaterialTheme.colorScheme.primary)
                else Modifier
            )
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal,
            color = if (selected) MaterialTheme.colorScheme.onPrimary
            else MaterialTheme.colorScheme.onSurface
        )
    }
}
