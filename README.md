# Topview/WeChat File Download Client

一个 Python 3.x 客户端 library/API，用于 downloading files from Topview.com 和 WeChat Chat Messages。

## Prerequisites

You'll need the following dependencies:

- `requests` (HTTP client)
- `pycrypt` (HTTPS authentication for Topview API)
- `urllib3` (proxy support - usually comes with requests)
- `json` (built-in Python standard library)

Install via pip:

```bash
pip install requests pycrypt
```

## Usage Examples

### Downloader Module
Downloading files from WeChat messages or Topview.com:

```python
from downloader import FileDownloader, WeChatDownloader
import os

download_dir = '/absolute/path/for/downloads'

# Create downloader instance
downloader = FileDownloader(download_dir)

# Download by URL
result = downloader.download_by_url('http://example.com/path/to/file.pdf')
print(f'Downloaded: {result["file_name"]}')

# Download by file ID (requires login/session)
result = downlayer.download_by_file_id('file_identifier', 'wechat_session_id')
```

### WeChat Integration
Download files from WeChat messages in the user's document folder:

```python
from downloader import WeChatDownloader, download_wechat_files_to_local
from wechat.downloader import DownWeChatMessageFile

# Configuration options
wechat_download_folder = '/absolute/path/for/downloads'  # Required

# Download recent files from a specific chat
config = {
    'chat_id': 'group-or-user-id',
    'limit': 10,
    'start_time': 'yyyy:yyy:mm:ss',
    'end_time': 'yyyy:yyy:mm:ss',
}
result = download_wechat_files_to_local(wechat_download_folder, **config)

# Download from a specific message ID and time
from downloader import DownWeChatMessageFile
file_path = DownWeChatMessageFile.download_from_id(msgid='msg-id-hao6o0a4h234q4y12', 
                                                    chatid='chat-id', 
                                                            timestamp=1234567890, 
                                                         download_folder='/absolute/path/')
```

### WeChat Session Management
Connect to a running WeChat instance for authentication or file download:

```python
from wechat.connect import ConnectToSession
from wechat.auth import Authenticator
import time

# Connect to local WeChat instance (Windows)
wechat_path = 'C:\\Program Files\\wechat\\WeChat.1.exe'
wechattoken = 'your_auth_token'
connect_result = ConnectToSession.connect_to(wechat_path, wechattoken)

if connect_result['status'] == 'connected':
    # Wait for file download to complete if needed
    time.sleep(20)  # Wait for files to be downloaded before proceeding
    
# Alternatively, authenticate as a user (not connected locally):
authenticator = Authenticator()
auth_token = authenticator.auth(username='user_id', password='password')
```

### Download History Tracking
Downloaded items are tracked in `download_history.json`:

```python
from downloader import DownloadTracker

tracker = DownloadTracker(history_file='/path/to/history.json')

# Get all downloads since 10/23 (for a specific date)
downloads_since = tracker.get_downloads_by_date(date_string='yyyy:yyy:mm:ss', 
                                                    from_download_time=10, 
                                                    to_download_time=23)

# Check download status of a group
group_id = 'some-group-id'
status = tracker.get_group_download_status(group_id)
```

## Supported Image Formats
The client automatically detects and downloads these image types:

- `.jpg`/`.jpeg`, `.png`, `.gif`, `.bmp`, `.webp`, `.svg`, `.tiff`
- `.mnn`, `.ar3qfz`, `.x15e486c6d27y30b91a63h4k5i9g2u4l1s3w4f6j7t9r0v4e1n2q5o7p0d8`, `.pngx`

### Automatic Image Detection
Any WeChat document with unknown format is treated as a possible image file. Currently known image formats (from the codebase):

- `.jpg`, `.jpeg`, `.png`, `.gif`, `.bmp`, `.webp`, `.svg`, `.tiff`, 
`.mnn`, `.ar3qfz`
#### Supported Image Formats

| Format | Status | Notes |
|--------|--------|-------|
| JPEG (JPG) | Primary Support | Largest format for photos |
| PNG | Supported via Pillow | Requires `Pillow >= 1.8` |
| TIFF | Secondary Support | Larger file size |


## Limitations

- Currently supports Windows platform only for WeChat integration (due to desktop app dependencies)
- Maximum file download size: 50 MB per request
- Only downloads images from messages sent within the last 99 days and limited to files larger than 1MB
- For other platforms, use `DownWeChatMessageFile.download_from_id` method for specific message files
