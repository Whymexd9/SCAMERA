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
        }
        selectedDisplayFolders.setValue(allFolders);
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
        if (list != null) for (GalleryItem i : list) if (copy.getFileUri().equals(i.getFile().getFileUri())) found = true;
        if (!found && list != null) {
            list = new ArrayList<>(list);
            list.add(0, new GalleryItem(copy));
            currentFolderImages.setValue(list);
        }
        pendingSelect = copy.getFileUri();
        setUpdatePending(true);
    }

    public MutableLiveData<List<GalleryItem>> getSelectedDisplayFolders() {
        return selectedDisplayFolders;
    }
}
