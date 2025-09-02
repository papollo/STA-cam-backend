# STA-cam-back Setup Instructions

## 1. Maven Dependencies

Add these dependencies to your `pom.xml` file in the `<dependencies>` section:

```xml
<!-- WebSocket for live feed -->
<dependency>
    <groupId>org.springframework.boot</groupId>
    <artifactId>spring-boot-starter-websocket</artifactId>
</dependency>

<!-- JPA for data persistence -->
<dependency>
    <groupId>org.springframework.boot</groupId>
    <artifactId>spring-boot-starter-data-jpa</artifactId>
</dependency>

<!-- H2 Database for development -->
<dependency>
    <groupId>com.h2database</groupId>
    <artifactId>h2</artifactId>
    <scope>runtime</scope>
</dependency>
```

## 2. Python Environment Setup

Install Python dependencies:
```bash
cd python_scripts
pip install -r requirements.txt
```

## 3. API Endpoints

### File Upload
- **POST** `/api/detection/upload`
- Form data with file parameter: `file`
- Accepts images (jpg, png, gif, bmp) and videos (mp4, avi, mov, wmv, mkv)

### Get Detection Results
- **GET** `/api/detection/results` - Get recent detection results
- **GET** `/api/detection/results/since?minutes=30` - Get results from last 30 minutes
- **GET** `/api/detection/results/{id}` - Get specific detection result

### WebSocket Live Feed
- **WebSocket** endpoint: `/ws`
- Topic: `/topic/detections` - Real-time detection updates
- Topic: `/topic/detections/initial` - Initial data for new subscribers

## 4. Frontend Integration Examples

### File Upload (JavaScript)
```javascript
const formData = new FormData();
formData.append('file', fileInput.files[0]);

fetch('http://localhost:8080/api/detection/upload', {
    method: 'POST',
    body: formData
})
.then(response => response.json())
.then(data => console.log('Upload result:', data));
```

### WebSocket Connection (JavaScript)
```javascript
const socket = new SockJS('http://localhost:8080/ws');
const stompClient = Stomp.over(socket);

stompClient.connect({}, function(frame) {
    stompClient.subscribe('/topic/detections', function(message) {
        const detection = JSON.parse(message.body);
        console.log('New detection:', detection);
    });
});
```

## 5. Directory Structure
The application will create an `uploads` folder for storing uploaded files.

## 6. Database Access
H2 Console available at: http://localhost:8080/h2-console
- JDBC URL: jdbc:h2:mem:testdb
- Username: sa
- Password: (empty)
