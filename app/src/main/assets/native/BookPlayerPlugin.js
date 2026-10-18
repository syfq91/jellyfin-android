export class BookPlayerPlugin {
    constructor({ events, playbackManager, appSettings, loading }) {
        window['BookPlayer'] = this;

        this.events = events;
        this.playbackManager = playbackManager;
        this.appSettings = appSettings;
        this.loading = loading;

        this.name = 'ReadiumBookPlayer';
        this.type = 'mediaplayer';
        this.id = 'readium';

        // Prioritize first over default web reader
        this.priority = -1;
        this.isLocalPlayer = true;

        this._nativeBookPlayer = window['NativeBookPlayer'];
    }

    async play(options) {
        const item = options.items && options.items.length > 0 ? options.items[0] : null;
        if (!item) return;

        const bookOptions = {
            id: item.Id,
            name: item.Name || 'Book',
            container: item.Container || '',
            playbackPositionTicks: (item.UserData && item.UserData.PlaybackPositionTicks) || 0,
        };

        if (this.loading) {
            this.loading.hide();
        }

        if (this._nativeBookPlayer) {
            this._nativeBookPlayer.openBook(JSON.stringify(bookOptions));
        }
    }

    shuffle(item) {}
    instantMix(item) {}
    queue(options) {}
    queueNext(options) {}

    canPlayMediaType(mediaType) {
        return mediaType === 'Book';
    }

    canQueueMediaType(mediaType) {
        return false;
    }

    canPlayItem(item, playOptions) {
        return this._nativeBookPlayer && this._nativeBookPlayer.isEnabled();
    }

    async stop(destroyPlayer) {
        if (this._nativeBookPlayer) {
            this._nativeBookPlayer.closeBook();
        }
    }

    destroy() {
        this.stop(true);
    }
}
