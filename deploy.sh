#!/bin/bash
# FinanceOS Deployment Script for Oracle Cloud Infrastructure
# This script automates the deployment process

set -e  # Exit on error

echo "========================================="
echo "FinanceOS Deployment Script"
echo "========================================="
echo ""

# Configuration
APP_DIR="/home/ubuntu/financeos-server"
JAR_NAME="backend-1.0.0.jar"
SERVICE_NAME="financeos"

# Navigate to application directory
cd "$APP_DIR"

echo "Step 1: Loading environment variables..."
if [ -f .env ]; then
    export $(cat .env | grep -v '^#' | xargs)
    echo "✓ Environment variables loaded"
else
    echo "✗ ERROR: .env file not found!"
    exit 1
fi

echo ""
echo "Step 2: Building application..."
./mvnw clean package -DskipTests
if [ $? -eq 0 ]; then
    echo "✓ Build successful"
else
    echo "✗ Build failed!"
    exit 1
fi

echo ""
echo "Step 3: Backing up current JAR..."
if [ -f "target/$JAR_NAME" ]; then
    cp "target/$JAR_NAME" "target/${JAR_NAME}.backup.$(date +%Y%m%d_%H%M%S)"
    echo "✓ Backup created"
fi

echo ""
echo "Step 4: Stopping existing service..."
sudo systemctl stop $SERVICE_NAME || true
echo "✓ Service stopped"

echo ""
echo "Step 5: Waiting for service to stop..."
sleep 3

echo ""
echo "Step 6: Starting service..."
sudo systemctl start $SERVICE_NAME
echo "✓ Service started"

echo ""
echo "Step 7: Checking service status..."
sleep 2
sudo systemctl status $SERVICE_NAME --no-pager

echo ""
echo "========================================="
echo "Deployment Complete!"
echo "========================================="
echo ""
echo "Useful commands:"
echo "  View logs:    sudo journalctl -u $SERVICE_NAME -f"
echo "  Stop service: sudo systemctl stop $SERVICE_NAME"
echo "  Restart:      sudo systemctl restart $SERVICE_NAME"
echo "  Status:       sudo systemctl status $SERVICE_NAME"
echo ""
