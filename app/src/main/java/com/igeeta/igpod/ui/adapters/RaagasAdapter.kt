package com.igeeta.igpod.ui.adapters

import androidx.appcompat.widget.PopupMenu
import androidx.fragment.app.Fragment
import com.igeeta.igpod.R
import com.igeeta.igpod.ui.MainActivity
import com.igeeta.igpod.ui.fragments.GeneralSubFragment
import uk.akane.libphonograph.items.RaagasItem

class RaagasAdapter(
    fragment: Fragment,
) : BaseAdapter<RaagasItem>
    (
    fragment,
    liveData = (fragment.requireActivity() as MainActivity).reader.raagasListFlow,
    sortHelper = StoreRaagasHelper,
    naturalOrderHelper = null,
    initialSortType = Sorter.Type.ByTitleAscending,
    pluralStr = R.plurals.items,
    defaultLayoutType = LayoutType.LIST
) {

    init {
        lateInit()
    }

    override val defaultCover = R.drawable.ic_default_cover_genre

    override fun virtualTitleOf(item: RaagasItem): String {
        return context.getString(R.string.unknown_genre)
    }

    override fun onClick(item: RaagasItem, position: Int) {
        mainActivity.startFragment(GeneralSubFragment()) {
            putString("Id", item.id?.toString())
            putInt("Item", R.id.raagas)
        }
    }

    override fun onMenu(item: RaagasItem, popupMenu: PopupMenu) {
        popupMenu.inflate(R.menu.more_menu_less)

        popupMenu.setOnMenuItemClickListener { it1 ->
            when (it1.itemId) {
                R.id.play_next -> {
                    val mediaController = mainActivity.getPlayer()
                    mediaController?.addMediaItems(
                        mediaController.currentMediaItemIndex + 1,
                        item.songList,
                    )
                    true
                }

                R.id.add_to_queue -> {
                    val mediaController = mainActivity.getPlayer()
                    mediaController?.addMediaItems(
                        item.songList,
                    )
                    true
                }

                else -> false
            }

        }
    }

    object StoreRaagasHelper : StoreItemHelper<RaagasItem>()
}
