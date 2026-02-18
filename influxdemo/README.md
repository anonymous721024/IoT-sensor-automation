<<<<<<< HEAD
# InfluxDB + Spring Boot Demo

This project demonstrates a Spring Boot web application that connects to **InfluxDB 3 Core**, queries data using SQL, and displays results in a neatly formatted HTML table using **Thymeleaf**.

## Features

- Connects to InfluxDB 3 Core (OSS) via HTTP
- Executes SQL queries using `/api/v3/query_sql`
- Parses JSON query results into Java objects
- Renders data as an HTML table (Thymeleaf)
- Provides both HTML and JSON endpoints for testing

## Architecture Overview

- **InfluxDB (port 8181)** stores the data
- **Spring Boot (port 8080/8081)** serves web pages and calls InfluxDB

### Data Flow

InfluxDB → Spring Boot (`InfluxClient`) → JSON → Java objects (`FoodRecord`) → Thymeleaf → HTML table

## Technologies Used

- Java 21
- Spring Boot
- InfluxDB 3 Core (OSS)
- Thymeleaf
- Maven

## Endpoints

- `GET /table` — HTML page showing results as a table  
  Example: `http://localhost:8080/table`

- `GET /api/foods` — returns raw JSON from InfluxDB  
  Example: `http://localhost:8080/api/foods`

## Configuration

Create a local config file at:

`src/main/resources/application.properties`

Example:

```properties
spring.application.name=influxdemo
influx.url=http://localhost:8181
influx.token=YOUR_TOKEN_HERE
influx.db=inflx_demo
=======
# IoT-sensor-automation
>>>>>>> 8972834 (first commit)
