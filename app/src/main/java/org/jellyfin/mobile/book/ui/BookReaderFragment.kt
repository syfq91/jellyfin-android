package org.jellyfin.mobile.book.ui

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.SeekBar
import android.widget.TextView
import androidx.constraintlayout.widget.ConstraintLayout
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.core.view.isVisible
import androidx.fragment.app.Fragment
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.google.android.material.appbar.MaterialToolbar
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import org.readium.adapter.pdfium.navigator.PdfiumEngineProvider
import org.jellyfin.mobile.R
import org.jellyfin.mobile.book.model.BookPlayOptions
import org.jellyfin.mobile.utils.BackPressInterceptor
import org.jellyfin.mobile.utils.Constants
import org.jellyfin.mobile.utils.extensions.getParcelableCompat
import org.koin.androidx.viewmodel.ext.android.viewModel
import org.readium.r2.navigator.VisualNavigator
import org.readium.r2.navigator.epub.EpubNavigatorFactory
import org.readium.r2.navigator.epub.EpubNavigatorFragment
import org.readium.r2.navigator.epub.EpubPreferences
import org.readium.r2.navigator.image.ImageNavigatorFragment
import org.readium.r2.navigator.input.InputListener
import org.readium.r2.navigator.input.TapEvent
import org.readium.r2.navigator.pdf.PdfNavigatorFactory
import org.readium.r2.navigator.pdf.PdfNavigatorFragment
import org.readium.r2.shared.ExperimentalReadiumApi
import org.readium.r2.shared.publication.Locator
import org.readium.r2.shared.publication.Publication
import timber.log.Timber

@OptIn(ExperimentalReadiumApi::class)
class BookReaderFragment : Fragment(), BackPressInterceptor {

    private val viewModel: BookReaderViewModel by viewModel()

    private var activeOptions: BookPlayOptions? = null
    private var currentPublication: Publication? = null
    private var currentNavigator: VisualNavigator? = null
    private var currentEpubPreferences: EpubPreferences = EpubPreferences()

    private lateinit var readerOverlay: ConstraintLayout
    private lateinit var topToolbar: MaterialToolbar
    private lateinit var progressSlider: SeekBar
    private lateinit var progressText: TextView
    private lateinit var loadingLayout: LinearLayout
    private lateinit var loadingProgressBar: ProgressBar
    private lateinit var loadingText: TextView
    private lateinit var errorLayout: LinearLayout
    private lateinit var errorText: TextView
    private lateinit var errorCloseButton: Button

    private var isOverlayVisible = true

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?,
    ): View {
        val root = inflater.inflate(R.layout.fragment_book_reader, container, false)

        readerOverlay = root.findViewById(R.id.reader_overlay)
        topToolbar = root.findViewById(R.id.top_toolbar)
        progressSlider = root.findViewById(R.id.progress_slider)
        progressText = root.findViewById(R.id.progress_text)
        loadingLayout = root.findViewById(R.id.loading_layout)
        loadingProgressBar = root.findViewById(R.id.loading_progress_bar)
        loadingText = root.findViewById(R.id.loading_text)
        errorLayout = root.findViewById(R.id.error_layout)
        errorText = root.findViewById(R.id.error_text)
        errorCloseButton = root.findViewById(R.id.error_close_button)

        topToolbar.setNavigationOnClickListener {
            close()
        }

        topToolbar.setOnMenuItemClickListener { menuItem ->
            when (menuItem.itemId) {
                R.id.action_toc -> {
                    currentPublication?.let { pub ->
                        val dialog = BookTableOfContentsDialog()
                        dialog.setup(pub) { link ->
                            currentNavigator?.go(link, animated = true)
                        }
                        dialog.show(childFragmentManager, BookTableOfContentsDialog.TAG)
                    }
                    true
                }
                R.id.action_settings -> {
                    val bottomSheet = BookSettingsBottomSheet()
                    bottomSheet.setup(currentEpubPreferences) { newPrefs ->
                        currentEpubPreferences = newPrefs
                        (currentNavigator as? EpubNavigatorFragment)?.submitPreferences(newPrefs)
                    }
                    bottomSheet.show(childFragmentManager, BookSettingsBottomSheet.TAG)
                    true
                }
                else -> false
            }
        }

        errorCloseButton.setOnClickListener {
            close()
        }

        progressSlider.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                if (fromUser) {
                    val percent = progress / 10
                    progressText.text = "$percent%"
                }
            }

            override fun onStartTrackingTouch(seekBar: SeekBar?) = Unit

            override fun onStopTrackingTouch(seekBar: SeekBar?) {
                val progress = seekBar?.progress ?: return
                val targetProgression = progress / 1000.0
                currentPublication?.let { pub ->
                    // Attempt to jump to approximate progression
                    val targetLocator = pub.tableOfContents.firstOrNull()?.let { pub.locatorFromLink(it) }
                        ?.copy(locations = Locator.Locations(totalProgression = targetProgression))
                    targetLocator?.let { currentNavigator?.go(it, animated = true) }
                }
            }
        })

        return root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        val options = arguments?.getParcelableCompat<BookPlayOptions>(Constants.EXTRA_BOOK_PLAY_OPTIONS)
        if (options == null) {
            Timber.e("No BookPlayOptions provided to BookReaderFragment")
            close()
            return
        }

        activeOptions = options
        topToolbar.title = options.name

        observeViewModel()

        if (savedInstanceState == null) {
            viewModel.loadBook(options)
        }
    }

    private fun observeViewModel() {
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.state.collectLatest { state ->
                    when (state) {
                        is BookReaderState.Loading -> {
                            loadingLayout.isVisible = true
                            errorLayout.isVisible = false
                            val percent = (state.progress * 100).toInt()
                            loadingProgressBar.progress = percent
                            loadingText.text = getString(R.string.book_reader_downloading, percent)
                        }
                        is BookReaderState.Ready -> {
                            loadingLayout.isVisible = false
                            errorLayout.isVisible = false
                            currentPublication = state.publication
                            setupNavigator(state.publication, state.initialLocator)
                        }
                        is BookReaderState.Error -> {
                            loadingLayout.isVisible = false
                            errorLayout.isVisible = true
                            errorText.text = state.message
                        }
                    }
                }
            }
        }
    }

    private fun setupNavigator(publication: Publication, initialLocator: Locator?) {
        if (childFragmentManager.findFragmentById(R.id.reader_container) != null) {
            return
        }

        val inputListener = object : InputListener {
            override fun onTap(event: TapEvent): Boolean {
                val width = view?.width ?: 1
                val relativeX = event.point.x / width
                if (relativeX in 0.25f..0.75f) {
                    toggleOverlay()
                    return true
                }
                return false
            }
        }

        when {
            publication.conformsTo(Publication.Profile.EPUB) -> {
                val factory = EpubNavigatorFactory(publication)
                childFragmentManager.fragmentFactory = factory.createFragmentFactory(
                    initialLocator = initialLocator,
                    listener = object : EpubNavigatorFragment.Listener {
                        override fun onExternalLinkActivated(url: org.readium.r2.shared.util.AbsoluteUrl) {
                            try {
                                val intent = android.content.Intent(android.content.Intent.ACTION_VIEW, android.net.Uri.parse(url.toString()))
                                startActivity(intent)
                            } catch (e: Exception) {
                                Timber.w(e, "Failed to open external link: %s", url)
                            }
                        }
                    },
                )

                val fragment = childFragmentManager.fragmentFactory.instantiate(
                    requireContext().classLoader,
                    EpubNavigatorFragment::class.java.name,
                ) as EpubNavigatorFragment

                fragment.addInputListener(inputListener)
                currentNavigator = fragment

                childFragmentManager.beginTransaction()
                    .replace(R.id.reader_container, fragment)
                    .commit()

                observeNavigator(fragment)
            }
            publication.conformsTo(Publication.Profile.PDF) -> {
                val factory = PdfNavigatorFactory(publication, org.readium.adapter.pdfium.navigator.PdfiumEngineProvider())
                childFragmentManager.fragmentFactory = factory.createFragmentFactory(
                    initialLocator = initialLocator,
                    listener = object : PdfNavigatorFragment.Listener {},
                )

                val fragment = childFragmentManager.fragmentFactory.instantiate(
                    requireContext().classLoader,
                    PdfNavigatorFragment::class.java.name,
                ) as PdfNavigatorFragment<*, *>

                fragment.addInputListener(inputListener)
                currentNavigator = fragment

                childFragmentManager.beginTransaction()
                    .replace(R.id.reader_container, fragment)
                    .commit()

                observeNavigator(fragment)
            }
            else -> {
                // Comics, manga, and image archives (DIVINA)
                val startLocator = initialLocator ?: publication.readingOrder.firstOrNull()?.let { publication.locatorFromLink(it) }
                childFragmentManager.fragmentFactory = ImageNavigatorFragment.createFactory(
                    publication = publication,
                    initialLocator = startLocator,
                    listener = object : ImageNavigatorFragment.Listener {},
                )

                val fragment = childFragmentManager.fragmentFactory.instantiate(
                    requireContext().classLoader,
                    ImageNavigatorFragment::class.java.name,
                ) as ImageNavigatorFragment

                fragment.addInputListener(inputListener)
                currentNavigator = fragment

                childFragmentManager.beginTransaction()
                    .replace(R.id.reader_container, fragment)
                    .commit()

                observeNavigator(fragment)
            }
        }
    }

    private fun observeNavigator(navigator: VisualNavigator) {
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                navigator.currentLocator.collectLatest { locator ->
                    viewModel.onLocatorChanged(locator)

                    val progression = locator.locations.totalProgression ?: 0.0
                    progressSlider.progress = (progression * 1000).toInt()
                    progressText.text = "${(progression * 100).toInt()}%"
                }
            }
        }
    }

    private fun toggleOverlay() {
        setOverlayVisible(!isOverlayVisible)
    }

    private fun setOverlayVisible(visible: Boolean) {
        isOverlayVisible = visible
        readerOverlay.isVisible = visible

        val activity = activity ?: return
        val windowInsetsController = WindowCompat.getInsetsController(activity.window, activity.window.decorView)

        if (visible) {
            windowInsetsController.show(WindowInsetsCompat.Type.systemBars())
        } else {
            windowInsetsController.hide(WindowInsetsCompat.Type.systemBars())
            windowInsetsController.systemBarsBehavior =
                WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }
    }

    private fun close() {
        viewModel.onReaderClosing()
        setOverlayVisible(true)
        parentFragmentManager.popBackStack()
    }

    override fun onInterceptBackPressed(): Boolean {
        close()
        return true
    }

    override fun onDestroyView() {
        setOverlayVisible(true)
        super.onDestroyView()
    }
}
