package io.github.touhougo.chogumi.feature.subjectdetails

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import io.github.touhougo.chogumi.core.common.notice.FailureNotices
import io.github.touhougo.chogumi.core.foundation.AppTime
import io.github.touhougo.chogumi.core.foundation.Load
import io.github.touhougo.chogumi.core.foundation.failureOrNull
import io.github.touhougo.chogumi.core.foundation.orEmpty
import io.github.touhougo.chogumi.core.common.viewmodel.StateViewModel
import io.github.touhougo.chogumi.core.data.repository.SubjectRepository
import io.github.touhougo.chogumi.core.model.CollectionEditing
import io.github.touhougo.chogumi.core.model.CollectionMarker
import io.github.touhougo.chogumi.core.model.SubjectRelationBo
import io.github.touhougo.chogumi.core.model.groupSubjectRelations
import io.github.touhougo.chogumi.core.model.displayNameOf
import io.github.touhougo.chogumi.core.res.R
import io.github.touhougo.chogumi.core.ui.collection.PosterMarkController
import io.github.touhougo.chogumi.core.ui.collection.PosterMarkHost
import io.github.touhougo.chogumi.core.ui.collection.PosterMarkState
import io.github.touhougo.chogumi.core.ui.common.SectionState
import io.github.touhougo.chogumi.core.ui.common.sectionStateOf
import io.github.touhougo.chogumi.core.ui.composables.EmptyLine
import io.github.touhougo.chogumi.core.ui.composables.FailureNoticeEffect
import io.github.touhougo.chogumi.core.ui.composables.PageScaffold
import io.github.touhougo.chogumi.core.ui.composables.SectionHeader
import kotlinx.coroutines.launch
import org.koin.compose.viewmodel.koinViewModel
import org.koin.core.parameter.parametersOf

/** 关联作品子页的状态：顶栏标题要条目名，内容是全部关联。两路都走仓库缓存，从详情页点进来通常直接命中。 */
data class SubjectRelationsUiState(
    val title: String? = null,
    val relations: Load<List<SubjectRelationBo>> = Load.Pending,
    /** 关联海报的长按标记，只由 [SubjectRelationsViewModel.posterMarks] 改。 */
    val posterMarks: PosterMarkState = PosterMarkState(),
)

/**
 * 关联作品子页的加载与重试。不复用详情页的 ViewModel：Navigation 3 每个 entry 各有 ViewModel，
 * 数据走仓库的本地缓存（与详情页同一份关联缓存），两页互不牵连。
 */
class SubjectRelationsViewModel(
    private val subjectId: Int,
    private val subjectRepository: SubjectRepository,
    collectionMarker: CollectionMarker,
    collectionEditing: CollectionEditing,
    appTime: AppTime,
) : StateViewModel<SubjectRelationsUiState>(SubjectRelationsUiState()) {

    /** 本页的失败提示：关联海报上长按标记的提交失败。 */
    val failures = FailureNotices()

    /** 关联海报的长按标记：标状态、撤销、收藏管理面板。 */
    val posterMarks = PosterMarkController(
        scope = viewModelScope,
        read = { currentState.posterMarks },
        write = { change -> updateState { it.copy(posterMarks = change(it.posterMarks)) } },
        failures = failures,
        marker = collectionMarker,
        editing = collectionEditing,
        today = appTime.today,
    )

    init {
        viewModelScope.launch {
            subjectRepository.getSubject(subjectId).collect { result ->
                if (result is Load.Ready) updateState { it.copy(title = displayNameOf(result.value)) }
            }
        }
        load()
    }

    fun retry() = load()

    private fun load() = launchSection("relations", failures, { subjectRepository.getRelations(subjectId) }, { it.relations }) {
        copy(relations = it)
    }
}

/**
 * 关联作品子页（原型 `relsScreen`、截图 10）：「幼女战记的关联作品」，按权重组分段（主线 / 衍生 / 音乐与周边 / 客串与其他），
 * 每组一个 3 列封面网格（[RelationCardGrid]）。分组与先后只由 [groupSubjectRelations]（关系 → 权重的唯一一张表）决定。
 */
@Composable
fun SubjectRelationsScreen(
    subjectId: Int,
    isLoggedIn: Boolean,
    onLogin: () -> Unit,
    onBack: () -> Unit,
    onSubjectClick: (Int) -> Unit,
) {
    val viewModel: SubjectRelationsViewModel = koinViewModel { parametersOf(subjectId) }
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }
    FailureNoticeEffect(viewModel.failures, snackbarHostState)
    val relations = state.relations.orEmpty()
    val sections = remember(relations) {
        groupSubjectRelations(relations).groupBy { it.kind.weight }
    }
    val title = state.title?.let { stringResource(R.string.detail_relations_title, it) }
        ?: stringResource(R.string.detail_related_works)
    val sectionState = sectionStateOf(state.relations)

    // 关联海报能长按标记
    PosterMarkHost(state.posterMarks, viewModel.posterMarks, isLoggedIn, onLogin, snackbarHostState, Modifier.fillMaxSize()) {
        PageScaffold(title = title, onBack = onBack) { padding ->
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding)
                    .verticalScroll(rememberScrollState()),
            ) {
                ListSection(
                    title = null,
                    state = sectionState,
                    error = state.relations.failureOrNull(),
                    onRetry = viewModel::retry,
                    skeleton = { RelationCardGridSkeleton() },
                ) {
                    sections.forEach { (weight, groups) ->
                        val cards = groups.toCardItems()
                        SectionHeader(title = stringResource(weight.labelRes()), count = cards.size)
                        RelationCardGrid(items = cards, onSubjectClick = onSubjectClick)
                    }
                }
                if (sectionState == SectionState.EMPTY) {
                    EmptyLine(stringResource(R.string.no_content))
                }
            }
        }
        // 收藏面板开着时提示条画在面板上
        if (!state.posterMarks.sheet.isShown) {
            SnackbarHost(snackbarHostState, Modifier.align(Alignment.BottomCenter).navigationBarsPadding())
        }
    }
}
