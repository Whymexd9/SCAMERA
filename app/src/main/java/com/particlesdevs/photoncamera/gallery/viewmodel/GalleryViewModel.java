package com.particlesdevs.photoncamera.gallery.viewmodel;

import android.app.Application;

import androidx.annotation.NonNull;
import androidx.lifecycle.AndroidViewModel;
import androidx.lifecycle.LiveData;
import androidx.lifecycle.MutableLiveData;

import com.particlesdevs.photoncamera.gallery.files.GalleryFileOperations;
import com.particlesdevs.photoncamera.gallery.files.ImageFile;
import com.particlesdevs.photoncamera.gallery.model.GalleryItem;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;
import java.util.stream.Collectors;

public class GalleryViewModel extends AndroidViewModel {
    private final MutableLiveData<GalleryItem> allSelectedImagesFolder = new MutableLiveData<>(GalleryItem.createEmpty());
    private final MutableLiveData<List<GalleryItem>> selectedDisplayFolders = new MutableLiveData<>(new ArrayList<>(0));

    private final MutableLiveData<List<GalleryItem>> currentFolderImages = new MutableLiveData<>(new ArrayList<>(0));
    private final MutableLiveData<Boolean> updatePendingLiveData=new MutableLiveData<>(false);


    public MutableLiveData<Boolean> getUpdatePending() {
        return updatePendingLiveData;
    }

    public void setUpdatePending(boolean updatePending) {
        updatePendingLiveData.setValue(updatePending);
    }

    public GalleryViewModel(@NonNull Application application) {
        super(application);
    }

    public void fetchAllMedia() {
        List<GalleryItem> allFolders=GalleryFileOperations._fetchSelectedFolders(getApplication().getContentResolver()).stream().map((Function<GalleryFileOperations.ImagesFolder, GalleryItem>) imagesFolder -> {
            GalleryItem folder = new GalleryItem(imagesFolder.getTopImage());
            imagesFolder.getAllImageFiles().forEach(imageFile -> folder.getFiles().add(new GalleryItem(imageFile)));
            folder.setDisplayName(imagesFolder.getFolderName());
            return folder;
        }).collect(Collectors.toList());
        
        ArrayList<ImageFile> all = (ArrayList<ImageFile>) GalleryFileOperations.extractAllSelectedImages();
        if (!all.isEmpty()) {
            GalleryItem ALL_Folder = new GalleryItem(all.get(0));
            ALL_Folder.setDisplayName("ALL");
            ALL_Folder.getFiles().addAll(all.stream().map(GalleryItem::new).collect(Collectors.toList()));
            allSelectedImagesFolder.setValue(ALL_Folder);

            allFolders.add(0, ALL_Folder);
        } else {
            // every selected folder is empty: no stale «ALL» (its deleted photos came back in the grid and the viewer)
            GalleryItem none = GalleryItem.createEmpty();
            none.setDisplayName("ALL");
            allSelectedImagesFolder.setValue(none);
        }
        selectedDisplayFolders.setValue(allFolders);
    }

    /**
     * The media lists read again and the folder named {@code keep} shown again («ALL» when it is gone or null), so the library's
     * grid and the viewer use the same list after a delete or a folders change. Returns the name of the folder shown (the first
     * chip when {@code keep} is gone), null without folders.
     */
    @androidx.annotation.Nullable
    public String refreshKeeping(@androidx.annotation.Nullable String keep) {
        fetchAllMedia();
        List<GalleryItem> folders = selectedDisplayFolders.getValue();
        GalleryItem shown = null;
        if (folders != null && keep != null) for (GalleryItem f : folders) if (keep.equals(f.getDisplayName())) shown = f;
        if (shown == null) {
            shown = allSelectedImagesFolder.getValue();
            keep = folders == null || folders.isEmpty() ? null : folders.get(0).getDisplayName();
        }
        setCurrentFolderImages(shown);
        return keep;
    }

    public LiveData<GalleryItem> getAllSelectedImageFolder() {
        return allSelectedImagesFolder;
    }

    public MutableLiveData<List<GalleryItem>> getCurrentFolderImages() {
        return currentFolderImages;
    }

    public void setCurrentFolderImages(GalleryItem currentFolder) {
        currentFolderName = currentFolder != null ? currentFolder.getDisplayName() : null;
        currentFolderImages.setValue(currentFolder.getFiles());
    }

    /** P351: the folder the viewer shows («ALL» for every selected folder), null for a list built by hand (another app's file). */
    @androidx.annotation.Nullable private String currentFolderName;
    /** P351: the photo the viewer moves to when it next binds its list (the editor's new copy), null for none. */
    @androidx.annotation.Nullable private android.net.Uri pendingSelect;

    /** The folder shown now («ALL» for every selected folder), null for a list built by hand. */
    @androidx.annotation.Nullable
    public String getCurrentFolderName() {
        return currentFolderName;
    }

    /** The photo the viewer moves to when it next binds its list (the photo another app asked to view), null for none. */
    public void selectNext(@androidx.annotation.Nullable android.net.Uri uri) {
        pendingSelect = uri;
    }

    @androidx.annotation.Nullable
    public android.net.Uri takePendingSelect() {
        android.net.Uri u = pendingSelect;
        pendingSelect = null;
        return u;
    }

    /**
     * P351: after «Сохранить копию»: the media lists read again, the viewer's folder rebuilt (or every folder), the copy put
     * first when the lists do not have it (a folder the gallery does not show, another app's file), and selected next.
     */
    public void showNewFile(ImageFile copy) {
        fetchAllMedia();
        GalleryItem folder = null;
        List<GalleryItem> folders = selectedDisplayFolders.getValue();
        if (folders != null) {
            for (GalleryItem f : folders)
                if (currentFolderName != null && currentFolderName.equals(f.getDisplayName())) folder = f;
            if (folder == null) folder = allSelectedImagesFolder.getValue();
        }
        List<GalleryItem> list;
        if (folder != null && currentFolderName != null) {
            setCurrentFolderImages(folder);
            list = currentFolderImages.getValue();
        } else {
            list = new ArrayList<>(currentFolderImages.getValue() != null ? currentFolderImages.getValue() : new ArrayList<>());
        }
        boolean found = false;
        android.net.Uri select = copy.getFileUri();
        if (list != null)
            for (GalleryItem i : list)
                if (sameMedia(copy, i.getFile())) {
                    found = true;
                    select = i.getFile().getFileUri(); // the list's own form of the row's URI (the viewer matches it)
                }
        if (!found && list != null) {
            list = new ArrayList<>(list);
            list.add(0, new GalleryItem(copy));
            currentFolderImages.setValue(list);
        }
        pendingSelect = select;
        setUpdatePending(true);
    }

    /**
     * The same MediaStore row: equal URIs, or equal row ids (content://media/external_primary/images/media/42 is the gallery's
     * content://media/external/images/media/42).
     */
    static boolean sameMedia(ImageFile a, @androidx.annotation.Nullable com.particlesdevs.photoncamera.gallery.files.MediaFile b) {
        if (b == null) return false;
        if (a.getFileUri() != null && a.getFileUri().equals(b.getFileUri())) return true;
        return a.getId() >= 0 && b instanceof ImageFile && a.getId() == b.getId();
    }

    public MutableLiveData<List<GalleryItem>> getSelectedDisplayFolders() {
        return selectedDisplayFolders;
    }
}
