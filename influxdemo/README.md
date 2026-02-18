# IoT Sensor Automation Platform

A full-stack IoT monitoring and automation system built with Spring Boot, InfluxDB 3, and a custom MCP server.
The platform provides real-time sensor monitoring, configurable alerting, and document-assisted troubleshooting through an administrative console.

---

## Overview

This project demonstrates an end-to-end architecture for managing environmental sensors in operational facilities.
It combines time-series data processing, a web-based admin interface, and an extensible backend designed for future AI-assisted diagnostics.

---

## Key Features

### Real-Time Monitoring

* Time-series visualization of sensor temperature readings
* Interactive chart with zoom, pan, and interval aggregation
* Configurable start time for historical analysis

### Alerting and Threshold Management

* Adjustable temperature thresholds
* Automatic overload detection
* Simulated data generation for testing scenarios

### Administrative Chat Interface

* Query system behaviour using natural language
* Uses uploaded procedures and working instructions as context
* Streaming responses with cited document sources

### Document Management

* Upload and manage GLOBAL or AREA-specific operational documents
* Document selection and filtering for chat context
* Inline document viewing

### System Administration

* Sensor registry management
* Central configuration settings
* Authentication and access control

---

## Architecture

The system is composed of three main layers:

1. Web Application (Spring Boot)

   * Admin UI (Thymeleaf templates)
   * Chat service and document retrieval
   * Security configuration

2. Time-Series Storage (InfluxDB 3)

   * Stores sensor telemetry
   * Supports SQL queries for analytics

3. MCP Server

   * Provides a lightweight SQL access layer
   * Enables external integrations

---

## Technology Stack

* Java 21
* Spring Boot
* Thymeleaf
* InfluxDB 3
* Chart.js
* Maven
* H2 (local metadata storage)

---

## Project Structure

```
influxdemo/            Main Spring Boot application
influx-mcp-server/     MCP SQL server
uploads/               Runtime document storage (gitignored)
storage/               Runtime data (gitignored)
```

---

## Running the Application

### 1. Clone the repository

```bash
git clone git@github.com:anonymous721024/IoT-sensor-automation.git
cd IoT-sensor-automation
```

### 2. Start the main application

```bash
cd influxdemo
./mvnw spring-boot:run
```

### 3. (Optional) Start MCP server

```bash
cd ../influx-mcp-server
mvn spring-boot:run
```

---

## Configuration

Application configuration is located at:

```
influxdemo/src/main/resources/application.properties
```

Configure the following as needed:

* InfluxDB endpoint
* Database name
* Authentication token

---

## Example Workflow

1. Register sensors in the Admin Sensors page
2. Generate or ingest sensor data
3. View historical data in Data Flow
4. Upload operational documents
5. Use Admin Chat to query procedures or diagnostics

---

## Purpose

This project was developed as part of an internship and learning initiative to explore:

* IoT data pipelines
* Time-series databases
* Full-stack system design
* Operational tooling for monitoring environments

---

## Author

George Widjaja
Bachelor of Information Technology (Software Development)

---

## License

This repository is intended for educational and demonstration purposes.
